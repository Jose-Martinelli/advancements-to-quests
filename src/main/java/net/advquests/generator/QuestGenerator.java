package net.advquests.generator;

import com.mojang.logging.LogUtils;
import net.advquests.config.AdvQuestsConfig;
import net.advquests.config.AdvQuestsConfig.GroupDef;
import net.advquests.config.AdvQuestsConfig.GroupMatch;
import net.advquests.snbt.Snbt;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gera os arquivos .snbt do FTB Quests (config/ftbquests/quests) a partir dos advancements carregados.
 * <p>
 * Tudo que este gerador cria recebe a tag "advquests". Na geracao seguinte, capitulos e grupos com essa
 * tag que nao existem mais sao removidos; o resto do questbook (feito a mao) nunca e tocado.
 * <p>
 * Os IDs sao derivados do ID do advancement (hash), entao sao sempre os mesmos: o progresso dos jogadores
 * e mantido entre geracoes.
 */
public final class QuestGenerator {
    public static final String MANAGED_TAG = "advquests";
    public static final String FILE_PREFIX = "advq_";
    private static final Logger LOGGER = LogUtils.getLogger();

    private final AdvQuestsConfig config;
    private final RewardResolver rewards; // null = nao mostrar recompensas
    private final CriteriaItemResolver criteriaItems; // null = sempre usar task de advancement
    /** Advancements que viraram quests de item (advancement -> ID da quest), para a sincronizacao. */
    private final Map<ResourceLocation, String> itemQuestAdvancements = new HashMap<>();
    private final Path questsDir;
    private final Path chaptersDir;

    public record Result(int groups, int chapters, int quests, int removedChapters,
                         Map<ResourceLocation, String> itemQuests) {
    }

    private record Tab(Advancement root, GroupMatch match, List<Advancement> members) {
    }

    public QuestGenerator(AdvQuestsConfig config, Path configDir, RewardResolver rewards,
                          CriteriaItemResolver criteriaItems) {
        this.config = config;
        this.rewards = rewards;
        this.criteriaItems = criteriaItems;
        this.questsDir = configDir.resolve("ftbquests").resolve("quests");
        this.chaptersDir = questsDir.resolve("chapters");
    }

    public Result generate(Collection<Advancement> all, boolean resetLayout) throws IOException {
        // 1) Filtra os advancements que viram quests e agrupa por aba (advancement raiz).
        Set<ResourceLocation> included = new HashSet<>();
        Map<ResourceLocation, Tab> tabs = new LinkedHashMap<>();

        List<Advancement> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(a -> a.getId().toString()));

        for (Advancement adv : sorted) {
            DisplayInfo display = adv.getDisplay();
            if (display == null) continue; // receitas e advancements tecnicos

            Advancement root = rootOf(adv);
            // Abas cuja raiz nao tem display nao aparecem nem no vanilla.
            if (root.getDisplay() == null) continue;

            String id = adv.getId().toString();
            String rootId = root.getId().toString();
            if (config.isExcluded(rootId) || config.isExcluded(id)) continue;
            if (display.isHidden() && "skip".equalsIgnoreCase(config.hiddenAdvancements) && adv != root) continue;

            included.add(adv.getId());
            tabs.computeIfAbsent(root.getId(), k -> new Tab(root, config.findGroup(rootId), new ArrayList<>()))
                    .members().add(adv);
        }

        // 2) Ordena as abas: pela ordem dos grupos na config, depois pela ordem do padrao, depois alfabetica.
        List<GroupDef> groupOrder = new ArrayList<>(config.groups);
        if (config.defaultGroup != null) groupOrder.add(config.defaultGroup);

        List<Tab> tabList = new ArrayList<>(tabs.values());
        tabList.sort(Comparator
                .comparingInt((Tab t) -> t.match().group() == null ? Integer.MAX_VALUE : groupOrder.indexOf(t.match().group()))
                .thenComparingInt(t -> t.match().order())
                .thenComparing(t -> t.root().getId().toString()));

        // 3) Escreve os capitulos.
        Files.createDirectories(chaptersDir);
        Set<String> writtenFiles = new HashSet<>();
        Map<GroupDef, Integer> orderInGroup = new HashMap<>();
        int questCount = 0;

        for (Tab tab : tabList) {
            GroupDef group = tab.match().group();
            int orderIndex = orderInGroup.merge(group == null ? NO_GROUP : group, 1, Integer::sum) - 1;
            String filename = chapterFilename(tab.root().getId());
            Map<String, Object> chapter = buildChapter(tab, group, orderIndex, filename, included, resetLayout);
            Snbt.writeFile(chaptersDir.resolve(filename + ".snbt"), chapter);
            writtenFiles.add(filename + ".snbt");
            questCount += tab.members().size();
        }

        // 4) Remove capitulos gerados antes que nao existem mais (ex.: mod removido do modpack).
        int removed = removeStaleChapters(writtenFiles);

        // 5) Atualiza chapter_groups.snbt preservando os grupos feitos a mao.
        Set<GroupDef> usedGroups = new HashSet<>();
        for (Tab t : tabList) {
            if (t.match().group() != null) usedGroups.add(t.match().group());
        }
        List<GroupDef> groupsToWrite = groupOrder.stream().filter(usedGroups::contains).toList();
        writeChapterGroups(groupsToWrite);

        return new Result(groupsToWrite.size(), tabList.size(), questCount, removed, Map.copyOf(itemQuestAdvancements));
    }

    private static final GroupDef NO_GROUP = new GroupDef();

    private Map<String, Object> buildChapter(Tab tab, GroupDef group, int orderIndex, String filename,
                                             Set<ResourceLocation> included, boolean resetLayout) {
        Advancement root = tab.root();
        DisplayInfo rootDisplay = root.getDisplay();

        Map<String, double[]> oldPositions = (!resetLayout && config.keepManualPositions)
                ? readPositions(chaptersDir.resolve(filename + ".snbt"))
                : Map.of();

        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("id", hexId("chapter:" + root.getId()));
        ch.put("group", group == null ? "" : groupId(group));
        ch.put("order_index", orderIndex);
        ch.put("filename", filename);
        ch.put("title", toJson(rootDisplay.getTitle()));
        String subtitle = toJson(rootDisplay.getDescription());
        if (!subtitle.isEmpty()) ch.put("subtitle", List.of(subtitle));
        putIcon(ch, rootDisplay.getIcon());
        ch.put("tags", List.of(MANAGED_TAG));
        ch.put("default_quest_shape", "");
        ch.put("default_hide_dependency_lines", false);
        if (!"default".equalsIgnoreCase(config.progressionMode)) {
            ch.put("progression_mode", config.progressionMode.toLowerCase());
        }

        List<Object> quests = new ArrayList<>();
        for (Advancement adv : tab.members()) {
            quests.add(buildQuest(adv, included, oldPositions));
        }
        ch.put("quests", quests);
        ch.put("quest_links", new ArrayList<>());
        return ch;
    }

    private Map<String, Object> buildQuest(Advancement adv, Set<ResourceLocation> included,
                                           Map<String, double[]> oldPositions) {
        DisplayInfo display = adv.getDisplay();
        String advId = adv.getId().toString();
        String questId = hexId("quest:" + advId);
        String frame = display.getFrame().getName(); // task | goal | challenge

        Map<String, Object> q = new LinkedHashMap<>();
        q.put("id", questId);
        q.put("title", toJson(display.getTitle()));
        String desc = toJson(display.getDescription());
        if (!desc.isEmpty()) q.put("description", List.of(desc));
        putIcon(q, display.getIcon());

        double[] pos = oldPositions.get(questId);
        if (pos != null) {
            q.put("x", pos[0]);
            q.put("y", pos[1]);
        } else {
            q.put("x", round(display.getX() * config.spacingX));
            q.put("y", round(display.getY() * config.spacingY));
        }

        String shape = config.shapeFor(frame);
        if (!shape.isEmpty()) q.put("shape", shape);
        double size = config.sizeFor(frame);
        if (size != 1.0) q.put("size", size);

        Advancement dep = adv.getParent();
        while (dep != null && !included.contains(dep.getId())) {
            dep = dep.getParent();
        }
        if (dep != null) {
            q.put("dependencies", List.of(hexId("quest:" + dep.getId())));
        }

        if (display.isHidden() && "invisible".equalsIgnoreCase(config.hiddenAdvancements)) {
            q.put("invisible", true);
        }

        List<Object> tasks = new ArrayList<>();
        CriteriaItemResolver.Plan plan = criteriaItems == null ? null : criteriaItems.resolve(adv);
        if (plan != null) {
            // Advancement de "obter/craftar item": os itens do criterio sao as proprias tasks da quest.
            // ALL = todas obrigatorias; ANY = todas opcionais (o FTB Quests conclui com qualquer uma).
            // Clicar numa task de item abre a receita (JEI/REI/EMI). Nao consome itens.
            // Quando o jogador ganha o advancement, AdvancementSync conclui a quest, para nunca divergir.
            for (CriteriaItemResolver.ItemReq req : plan.items()) {
                String itemId = BuiltInRegistries.ITEM.getKey(req.item()).toString();
                Map<String, Object> itemTask = new LinkedHashMap<>();
                itemTask.put("id", hexId("itemtask:" + advId + ":" + itemId));
                itemTask.put("type", "item");
                itemTask.put("item", itemId);
                if (req.count() > 1) itemTask.put("count", (long) req.count());
                itemTask.put("consume_items", false);
                if (req.onlyFromCrafting()) itemTask.put("only_from_crafting", true);
                if (plan.mode() == CriteriaItemResolver.Mode.ANY) itemTask.put("optional_task", true);
                tasks.add(itemTask);
            }
            itemQuestAdvancements.put(adv.getId(), questId);
        } else {
            // Qualquer outro advancement: task de advancement, com o icone original dele.
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", hexId("task:" + advId));
            task.put("type", "advancement");
            task.put("advancement", advId);
            task.put("criterion", "");
            putIcon(task, display.getIcon());
            tasks.add(task);
        }
        q.put("tasks", tasks);

        // Recompensas apenas para exibicao: tipo "custom" nao entrega nada e "no_toast" resgata sozinho,
        // em silencio. O premio real continua vindo do advancement vanilla, entao nao ha duplicacao.
        if (rewards != null) {
            List<Object> rewardList = new ArrayList<>();
            for (RewardResolver.DisplayReward r : rewards.resolve(adv)) {
                Map<String, Object> reward = new LinkedHashMap<>();
                reward.put("id", hexId("reward:" + advId + ":" + r.key()));
                reward.put("type", "custom");
                reward.put("title", r.titleJson());
                putIcon(reward, r.icon());
                reward.put("auto", "no_toast");
                reward.put("exclude_from_claim_all", true);
                reward.put("tags", List.of(MANAGED_TAG));
                rewardList.add(reward);
            }
            if (!rewardList.isEmpty()) q.put("rewards", rewardList);
        }
        return q;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, double[]> readPositions(Path chapterFile) {
        Map<String, Object> old = Snbt.readFile(chapterFile);
        Map<String, double[]> result = new HashMap<>();
        if (old == null || !(old.get("quests") instanceof List<?> list)) return result;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> quest = (Map<String, Object>) m;
                if (quest.get("id") instanceof String id
                        && quest.get("x") instanceof Number x
                        && quest.get("y") instanceof Number y) {
                    result.put(id.toUpperCase(), new double[]{x.doubleValue(), y.doubleValue()});
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void writeChapterGroups(List<GroupDef> ours) throws IOException {
        Path file = questsDir.resolve("chapter_groups.snbt");
        List<Object> result = new ArrayList<>();

        // Mantem os grupos que nao foram criados por este mod.
        Map<String, Object> existing = Snbt.readFile(file);
        if (existing != null && existing.get("chapter_groups") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && !isManaged((Map<String, Object>) m)) {
                    result.add(m);
                }
            }
        }

        for (GroupDef g : ours) {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("id", groupId(g));
            tag.put("title", g.title);
            if (!g.icon.isBlank()) tag.put("icon", g.icon);
            tag.put("tags", List.of(MANAGED_TAG));
            result.add(tag);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("chapter_groups", result);
        Snbt.writeFile(file, root);
    }

    private static boolean isManaged(Map<String, Object> obj) {
        return obj.get("tags") instanceof List<?> tags && tags.contains(MANAGED_TAG);
    }

    private int removeStaleChapters(Set<String> keep) throws IOException {
        int removed = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(chaptersDir, "*.snbt")) {
            for (Path p : ds) {
                if (keep.contains(p.getFileName().toString())) continue;
                Map<String, Object> data = Snbt.readFile(p);
                if (data != null && isManaged(data)) {
                    Files.delete(p);
                    removed++;
                    LOGGER.info("[advquests] Capitulo removido (aba nao existe mais): {}", p.getFileName());
                }
            }
        }
        return removed;
    }

    private static Advancement rootOf(Advancement adv) {
        Advancement a = adv;
        while (a.getParent() != null) a = a.getParent();
        return a;
    }

    static String chapterFilename(ResourceLocation rootId) {
        String base = (rootId.getNamespace() + "_" + rootId.getPath())
                .toLowerCase()
                .replaceAll("[^a-z0-9_]", "_");
        if (base.endsWith("_root")) base = base.substring(0, base.length() - 5);
        return FILE_PREFIX + base;
    }

    private static String groupId(GroupDef g) {
        return hexId("group:" + g.id);
    }

    /** ID deterministico no formato do FTB Quests (16 digitos hex, positivo, diferente de 0 e 1). */
    static String hexId(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xFF);
            v &= 0x7FFFFFFFFFFFFFFFL;
            if (v <= 1) v += 2;
            return String.format("%016X", v);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Texto do advancement como JSON (o FTB Quests interpreta e traduz no idioma do jogador). */
    private static String toJson(Component c) {
        if (c == null || c.getString().isBlank()) {
            return "";
        }
        return Component.Serializer.toJson(c);
    }

    private static void putIcon(Map<String, Object> target, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            target.put("icon", itemId);
        } else {
            Map<String, Object> icon = new LinkedHashMap<>();
            icon.put("id", itemId);
            icon.put("Count", (byte) 1);
            icon.put("tag", fromNbt(tag));
            target.put("icon", icon);
        }
    }

    /** Converte NBT vanilla para o modelo usado pelo escritor SNBT. */
    private static Object fromNbt(Tag tag) {
        if (tag instanceof CompoundTag c) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : c.getAllKeys()) m.put(k, fromNbt(c.get(k)));
            return m;
        }
        if (tag instanceof ListTag l) {
            List<Object> out = new ArrayList<>();
            for (Tag t : l) out.add(fromNbt(t));
            return out;
        }
        if (tag instanceof ByteArrayTag b) return b.getAsByteArray();
        if (tag instanceof IntArrayTag i) return i.getAsIntArray();
        if (tag instanceof LongArrayTag l) return l.getAsLongArray();
        if (tag instanceof NumericTag n) {
            return switch (tag.getId()) {
                case Tag.TAG_BYTE -> n.getAsByte();
                case Tag.TAG_SHORT -> n.getAsShort();
                case Tag.TAG_INT -> n.getAsInt();
                case Tag.TAG_LONG -> n.getAsLong();
                case Tag.TAG_FLOAT -> n.getAsFloat();
                default -> n.getAsDouble();
            };
        }
        return tag.getAsString();
    }
}
