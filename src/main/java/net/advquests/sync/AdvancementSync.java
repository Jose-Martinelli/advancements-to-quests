package net.advquests.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quests de item (sem task de advancement) sao concluidas pelo FTB Quests quando o jogador tem os itens.
 * Para elas NUNCA ficarem atras do advancement, este sincronizador conclui a quest quando o jogador ganha
 * o advancement correspondente, e tambem para advancements ganhos antes (ao entrar no mundo).
 * <p>
 * Usa o comando {@code /ftbquests change_progress <jogador> complete <quest>}, para nao depender do FTB Quests
 * em tempo de compilacao. Guarda em {@code <mundo>/advquests_synced.json} o que ja foi sincronizado, para
 * nao repetir a cada login.
 */
public final class AdvancementSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SYNCED_TYPE = new TypeToken<Map<String, Set<String>>>() {}.getType();
    private static final int LOGIN_DELAY_TICKS = 40; // espera o FTB Quests/Teams criar os dados do jogador

    /** advancement -> ID da quest de item */
    private static volatile Map<ResourceLocation, String> itemQuests = Map.of();
    private static Map<String, Set<String>> synced;
    private static final Deque<Pending> queue = new ArrayDeque<>();

    private record Pending(UUID player, int runAtTick, boolean fullCheck, ResourceLocation advancement) {
    }

    private AdvancementSync() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(AdvancementSync::onAdvancementEarned);
        MinecraftForge.EVENT_BUS.addListener(AdvancementSync::onLogin);
        MinecraftForge.EVENT_BUS.addListener(AdvancementSync::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(AdvancementSync::onServerStopped);
    }

    /** Chamado depois de cada geracao. Agenda a verificacao de todos os jogadores online. */
    public static void setItemQuests(MinecraftServer server, Map<ResourceLocation, String> quests) {
        itemQuests = Map.copyOf(quests);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            queue.add(new Pending(p.getUUID(), server.getTickCount() + 1, true, null));
        }
    }

    private static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        ResourceLocation id = event.getAdvancement().getId();
        if (!itemQuests.containsKey(id) || !(event.getEntity() instanceof ServerPlayer player)) return;
        // Executa no proximo tick: o evento dispara no meio da concessao do advancement.
        queue.add(new Pending(player.getUUID(), player.server.getTickCount() + 1, false, id));
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (itemQuests.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) return;
        queue.add(new Pending(player.getUUID(), player.server.getTickCount() + LOGIN_DELAY_TICKS, true, null));
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || queue.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        int now = server.getTickCount();
        int n = queue.size();
        for (int i = 0; i < n; i++) {
            Pending p = queue.poll();
            if (p == null) break;
            if (p.runAtTick() > now) {
                queue.add(p);
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(p.player());
            if (player == null) continue;
            if (p.fullCheck()) {
                for (ResourceLocation adv : itemQuests.keySet()) syncOne(server, player, adv);
            } else {
                syncOne(server, player, p.advancement());
            }
        }
    }

    private static void syncOne(MinecraftServer server, ServerPlayer player, ResourceLocation advId) {
        String questId = itemQuests.get(advId);
        if (questId == null || !ModList.get().isLoaded("ftbquests")) return;
        Advancement adv = server.getAdvancements().getAdvancement(advId);
        if (adv == null || !player.getAdvancements().getOrStartProgress(adv).isDone()) return;

        Set<String> done = synced(server).computeIfAbsent(player.getStringUUID(), k -> new HashSet<>());
        if (done.contains(questId)) return;

        String cmd = "ftbquests change_progress " + player.getStringUUID() + " complete " + questId;
        int result = server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), cmd);
        if (result > 0) { // falha (ex.: questbook ainda nao carregado) tenta de novo no proximo login
            done.add(questId);
            save(server);
        }
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("advquests_synced.json");
    }

    private static Map<String, Set<String>> synced(MinecraftServer server) {
        if (synced == null) {
            synced = new HashMap<>();
            Path f = file(server);
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    Map<String, Set<String>> loaded = GSON.fromJson(r, SYNCED_TYPE);
                    if (loaded != null) loaded.forEach((k, v) -> synced.put(k, new HashSet<>(v)));
                } catch (Exception e) {
                    LOGGER.warn("[advquests] Nao foi possivel ler {}", f, e);
                }
            }
        }
        return synced;
    }

    private static void save(MinecraftServer server) {
        try {
            Files.writeString(file(server), GSON.toJson(synced), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[advquests] Nao foi possivel salvar o estado de sincronizacao", e);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        synced = null;
        queue.clear();
        itemQuests = Map.of();
    }
}
