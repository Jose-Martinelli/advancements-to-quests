package net.advquests.client;

import com.mojang.logging.LogUtils;
import net.advquests.config.AdvQuestsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Faz o botao "Advancements" (menu de pause) e a tecla de advancements (L) abrirem o FTB Quests.
 * <p>
 * Roda so no cliente. Chama o FTB Quests por reflexao para o mod continuar sem depender dele para compilar.
 */
public final class AdvancementScreenRedirect {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CLIENT_QUEST_FILE = "dev.ftb.mods.ftbquests.client.ClientQuestFile";

    private static AdvQuestsConfig cachedConfig;
    private static long cachedMtime = Long.MIN_VALUE;
    private static Method openGui;
    private static boolean reflectionFailed;

    private AdvancementScreenRedirect() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(AdvancementScreenRedirect::onScreenOpening);
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof AdvancementsScreen)) return;

        AdvQuestsConfig config = config();
        if (!config.replaceAdvancementsScreen) return;
        // Shift segurado = abre a tela vanilla normalmente.
        if (config.vanillaScreenWithShift && Screen.hasShiftDown()) return;
        // Sem questbook carregado (ex.: servidor sem FTB Quests), deixa abrir a tela vanilla.
        if (!questBookAvailable()) return;

        event.setCanceled(true);
        // Abre no proximo tick: abrir outra tela de dentro do evento de abertura causaria recursao.
        Minecraft.getInstance().tell(AdvancementScreenRedirect::openQuestBook);
    }

    private static boolean questBookAvailable() {
        if (reflectionFailed || !ModList.get().isLoaded("ftbquests")) return false;
        try {
            Class<?> cls = Class.forName(CLIENT_QUEST_FILE);
            Object instance = cls.getField("INSTANCE").get(null);
            if (instance == null) return false;
            if (openGui == null) openGui = cls.getMethod("openGui");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            reflectionFailed = true;
            LOGGER.warn("[advquests] Nao foi possivel acessar o FTB Quests; a tela de advancements vanilla sera usada.", e);
            return false;
        }
    }

    private static void openQuestBook() {
        try {
            openGui.invoke(null);
        } catch (ReflectiveOperationException e) {
            reflectionFailed = true;
            LOGGER.warn("[advquests] Falha ao abrir o FTB Quests.", e);
        }
    }

    /** Le a config do disco so quando o arquivo muda (assim da para editar com o jogo aberto). */
    private static AdvQuestsConfig config() {
        Path dir = FMLPaths.CONFIGDIR.get();
        Path file = dir.resolve(AdvQuestsConfig.FILE_NAME);
        try {
            long mtime = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
            if (cachedConfig == null || mtime != cachedMtime) {
                cachedConfig = AdvQuestsConfig.load(dir);
                cachedMtime = Files.getLastModifiedTime(file).toMillis();
            }
        } catch (Exception e) {
            if (cachedConfig == null) {
                LOGGER.warn("[advquests] Erro ao ler config/{}; usando valores padrao.", AdvQuestsConfig.FILE_NAME, e);
                cachedConfig = new AdvQuestsConfig();
            }
        }
        return cachedConfig;
    }
}
