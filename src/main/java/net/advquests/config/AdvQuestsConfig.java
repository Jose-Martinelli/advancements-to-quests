package net.advquests.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuracao lida de config/advquests.json (aceita comentarios // e /* */).
 * Os nomes dos campos no JSON sao em snake_case (ex.: spacingX -> spacing_x).
 */
public final class AdvQuestsConfig {
    public static final String FILE_NAME = "advquests.json";
    private static final String DEFAULT_RESOURCE = "/advquests_default_config.json";

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    public boolean enabled = true;
    public boolean regenerateOnStart = true;
    public boolean keepManualPositions = true;
    public boolean replaceAdvancementsScreen = true;
    public boolean vanillaScreenWithShift = true;
    public String progressionMode = "flexible";
    public boolean showRewards = true;
    public int maxRewardItems = 8;
    public String itemDisplay = "requirements";
    public int maxItemTasks = 6;
    public String hiddenAdvancements = "invisible";
    public double spacingX = 1.5;
    public double spacingY = 1.5;
    public Map<String, String> shapes = new LinkedHashMap<>(Map.of(
            "task", "square", "goal", "rsquare", "challenge", "gear"));
    public Map<String, Double> sizes = new LinkedHashMap<>(Map.of(
            "task", 1.0, "goal", 1.25, "challenge", 1.5));
    public List<String> exclude = new ArrayList<>();
    public GroupDef defaultGroup = null;
    public List<GroupDef> groups = new ArrayList<>();

    public static final class GroupDef {
        public String id = "";
        public String title = "";
        public String icon = "";
        public List<String> match = new ArrayList<>();
    }

    /** Resultado de encaixar uma aba (advancement raiz) em um grupo. */
    public record GroupMatch(GroupDef group, int order) {
    }

    public GroupMatch findGroup(String rootId) {
        for (GroupDef g : groups) {
            for (int i = 0; i < g.match.size(); i++) {
                if (Glob.matches(g.match.get(i), rootId)) {
                    return new GroupMatch(g, i);
                }
            }
        }
        return new GroupMatch(defaultGroup, Integer.MAX_VALUE);
    }

    public boolean isExcluded(String advancementId) {
        for (String p : exclude) {
            if (Glob.matches(p, advancementId)) return true;
        }
        return false;
    }

    public String shapeFor(String frame) {
        return shapes.getOrDefault(frame, "");
    }

    public double sizeFor(String frame) {
        Double d = sizes.get(frame);
        return d == null ? 1.0 : d;
    }

    /** Carrega a config; se nao existir, cria a partir do padrao embutido no jar. */
    public static AdvQuestsConfig load(Path configDir) throws IOException {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            Files.createDirectories(configDir);
            Files.writeString(file, defaultText(), StandardCharsets.UTF_8);
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(r);
        }
    }

    public static AdvQuestsConfig parse(Reader reader) {
        JsonReader json = new JsonReader(reader);
        json.setLenient(true); // permite comentarios
        AdvQuestsConfig cfg = GSON.fromJson(json, AdvQuestsConfig.class);
        if (cfg == null) cfg = new AdvQuestsConfig();
        cfg.sanitize();
        return cfg;
    }

    private void sanitize() {
        if (shapes == null) shapes = new LinkedHashMap<>();
        if (sizes == null) sizes = new LinkedHashMap<>();
        if (exclude == null) exclude = new ArrayList<>();
        if (itemDisplay == null) itemDisplay = "requirements";
        if (groups == null) groups = new ArrayList<>();
        groups.removeIf(g -> g == null || g.id == null || g.id.isBlank());
        for (GroupDef g : groups) fixGroup(g);
        if (defaultGroup != null) {
            if (defaultGroup.id == null || defaultGroup.id.isBlank()) defaultGroup.id = "other";
            fixGroup(defaultGroup);
        }
        if (progressionMode == null) progressionMode = "default";
        if (hiddenAdvancements == null) hiddenAdvancements = "invisible";
        if (spacingX <= 0) spacingX = 1.5;
        if (spacingY <= 0) spacingY = 1.5;
    }

    private static void fixGroup(GroupDef g) {
        if (g.title == null || g.title.isBlank()) g.title = g.id;
        if (g.icon == null) g.icon = "";
        if (g.match == null) g.match = new ArrayList<>();
    }

    public static String defaultText() throws IOException {
        try (InputStream in = AdvQuestsConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) throw new IOException("Config padrao nao encontrada no jar: " + DEFAULT_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
