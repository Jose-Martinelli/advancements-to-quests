package net.advquests;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.advquests.config.AdvQuestsConfig;
import net.advquests.generator.CriteriaItemResolver;
import net.advquests.generator.QuestGenerator;
import net.advquests.generator.RewardResolver;
import net.advquests.sync.AdvancementSync;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;

@Mod(AdvQuests.MOD_ID)
public final class AdvQuests {
    public static final String MOD_ID = "advquests";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AdvQuests() {
        // ServerStartingEvent roda DEPOIS dos advancements serem carregados e ANTES do FTB Quests ler
        // config/ftbquests/quests (ele le no ServerStartedEvent). Entao os arquivos gerados aqui ja
        // entram no carregamento normal do FTB Quests.
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        AdvancementSync.register();

        // So no cliente: redireciona a tela de advancements para o FTB Quests.
        // A classe e referenciada apenas aqui, entao nunca e carregada em servidor dedicado.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            net.advquests.client.AdvancementScreenRedirect.register();
        }
    }

    private void onServerStarting(ServerStartingEvent event) {
        try {
            AdvQuestsConfig config = AdvQuestsConfig.load(configDir());
            if (!config.enabled || !config.regenerateOnStart) {
                LOGGER.info("[advquests] Geracao automatica desativada na config.");
                return;
            }
            QuestGenerator.Result r = generate(event.getServer(), config, false);
            LOGGER.info("[advquests] Gerados {} grupos, {} capitulos e {} quests ({} com tasks de item; {} capitulos antigos removidos).",
                    r.groups(), r.chapters(), r.quests(), r.itemQuests().size(), r.removedChapters());
        } catch (Exception e) {
            LOGGER.error("[advquests] Falha ao gerar as quests a partir dos advancements", e);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal(MOD_ID)
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("regenerate")
                        .executes(c -> regenerateCommand(c.getSource(), false))
                        .then(Commands.literal("reset_layout")
                                .executes(c -> regenerateCommand(c.getSource(), true)))));
    }

    private static int regenerateCommand(CommandSourceStack source, boolean resetLayout) {
        try {
            AdvQuestsConfig config = AdvQuestsConfig.load(configDir());
            if (!config.enabled) {
                source.sendFailure(Component.literal("advquests esta desativado em config/" + AdvQuestsConfig.FILE_NAME));
                return 0;
            }
            MinecraftServer server = source.getServer();

            // 1) Salva o mundo ANTES de gerar: assim o FTB Quests grava o progresso dos times e qualquer edicao
            //    pendente do questbook agora, e nao num autosave depois, por cima dos arquivos novos.
            server.saveEverything(true, false, true);

            QuestGenerator.Result r = generate(server, config, resetLayout);

            // 2) Recarrega o questbook do FTB Quests. Precisa rodar como SERVIDOR: executado como jogador, o
            //    FTB Quests recusa o reload se o jogador nao estiver no modo de edicao.
            int reloaded = server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), "ftbquests reload");

            source.sendSuccess(() -> Component.literal(String.format(
                    "Gerados %d grupos, %d capitulos e %d quests (%d com tasks de item) a partir dos advancements.",
                    r.groups(), r.chapters(), r.quests(), r.itemQuests().size())).withStyle(ChatFormatting.GREEN), true);
            if (reloaded <= 0) {
                source.sendFailure(Component.literal(
                        "Nao foi possivel recarregar o FTB Quests; as quests novas aparecem ao reiniciar o servidor."));
            }
            return r.quests();
        } catch (Exception e) {
            LOGGER.error("[advquests] Falha no /advquests regenerate", e);
            source.sendFailure(Component.literal("Erro ao gerar quests: " + e.getMessage()));
            return 0;
        }
    }

    private static QuestGenerator.Result generate(MinecraftServer server, AdvQuestsConfig config, boolean resetLayout)
            throws java.io.IOException {
        RewardResolver rewards = config.showRewards ? new RewardResolver(server, config.maxRewardItems) : null;
        CriteriaItemResolver items = !"off".equalsIgnoreCase(config.itemDisplay)
                ? new CriteriaItemResolver(server, config.maxItemTasks) : null;
        QuestGenerator.Result result = new QuestGenerator(config, configDir(), rewards, items)
                .generate(server.getAdvancements().getAllAdvancements(), resetLayout);
        AdvancementSync.setItemQuests(server, result.itemQuests());
        return result;
    }

    private static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
