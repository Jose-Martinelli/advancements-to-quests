package net.advquests.generator;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.Deserializers;
import net.minecraft.world.level.storage.loot.LootTable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Descobre o que um advancement da de recompensa (XP, itens de loot table, receitas) para MOSTRAR no FTB Quests.
 * <p>
 * As recompensas viram rewards do tipo "custom" do FTB Quests, que nao entregam nada ao serem resgatadas,
 * com resgate automatico e silencioso. Quem continua entregando o premio de verdade e o advancement vanilla,
 * entao nada e duplicado.
 */
public final class RewardResolver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_NESTED_TABLES = 4;

    private final MinecraftServer server;
    private final int maxItems;
    private Gson lootGson;

    /** Uma recompensa a ser exibida: chave estavel (para o ID), icone e titulo em JSON. */
    public record DisplayReward(String key, ItemStack icon, String titleJson) {
    }

    public RewardResolver(MinecraftServer server, int maxItems) {
        this.server = server;
        this.maxItems = maxItems;
    }

    public List<DisplayReward> resolve(Advancement adv) {
        List<DisplayReward> out = new ArrayList<>();
        JsonElement json;
        try {
            json = adv.getRewards().serializeToJson();
        } catch (Exception e) {
            return out;
        }
        if (json == null || !json.isJsonObject()) return out;
        JsonObject obj = json.getAsJsonObject();

        int xp = obj.has("experience") ? obj.get("experience").getAsInt() : 0;
        if (xp > 0) {
            Component title = Component.translatableWithFallback("advquests.reward.xp", "%s XP", xp);
            out.add(new DisplayReward("xp", new ItemStack(Items.EXPERIENCE_BOTTLE), json(title)));
        }

        if (obj.has("loot")) {
            Map<Item, Integer> items = new LinkedHashMap<>();
            for (JsonElement e : obj.getAsJsonArray("loot")) {
                collectLoot(new ResourceLocation(e.getAsString()), items, 0);
            }
            int n = 0;
            for (Map.Entry<Item, Integer> entry : items.entrySet()) {
                if (n++ >= maxItems) break;
                ItemStack stack = new ItemStack(entry.getKey());
                int count = entry.getValue();
                Component title = count > 1
                        ? Component.translatableWithFallback("advquests.reward.item_count", "%s x%s", stack.getHoverName(), count)
                        : stack.getHoverName();
                out.add(new DisplayReward("item:" + BuiltInRegistries.ITEM.getKey(entry.getKey()), stack, json(title)));
            }
        }

        if (obj.has("recipes")) {
            for (JsonElement e : obj.getAsJsonArray("recipes")) {
                ResourceLocation id = new ResourceLocation(e.getAsString());
                ItemStack result = server.getRecipeManager().byKey(id)
                        .map(r -> r.getResultItem(server.registryAccess()).copy())
                        .orElse(ItemStack.EMPTY);
                if (result.isEmpty()) continue;
                result.setCount(1);
                Component title = Component.translatableWithFallback("advquests.reward.recipe", "Recipe: %s", result.getHoverName());
                out.add(new DisplayReward("recipe:" + id, result, json(title)));
            }
        }
        return out;
    }

    private void collectLoot(ResourceLocation tableId, Map<Item, Integer> items, int depth) {
        if (depth > MAX_NESTED_TABLES) return;
        try {
            LootTable table = server.getLootData().getLootTable(tableId);
            if (table == LootTable.EMPTY) return;
            if (lootGson == null) lootGson = Deserializers.createLootTableSerializer().create();
            JsonElement tableJson = lootGson.toJsonTree(table);
            if (!tableJson.isJsonObject() || !tableJson.getAsJsonObject().has("pools")) return;
            for (JsonElement pool : tableJson.getAsJsonObject().getAsJsonArray("pools")) {
                if (pool.isJsonObject() && pool.getAsJsonObject().has("entries")) {
                    collectEntries(pool.getAsJsonObject().getAsJsonArray("entries"), items, depth);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[advquests] Nao foi possivel ler a loot table {}", tableId, e);
        }
    }

    private void collectEntries(JsonArray entries, Map<Item, Integer> items, int depth) {
        for (JsonElement el : entries) {
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            String type = entry.has("type") ? entry.get("type").getAsString() : "";
            if (type.endsWith("item") && entry.has("name")) {
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(entry.get("name").getAsString()));
                if (item != Items.AIR) items.merge(item, fixedCount(entry), Math::max);
            } else if (type.endsWith("loot_table") && entry.has("name") && entry.get("name").isJsonPrimitive()) {
                collectLoot(new ResourceLocation(entry.get("name").getAsString()), items, depth + 1);
            }
            if (entry.has("children") && entry.get("children").isJsonArray()) {
                collectEntries(entry.getAsJsonArray("children"), items, depth);
            }
        }
    }

    /** Quantidade fixa definida por minecraft:set_count (ou 1 se for aleatoria/ausente). */
    private static int fixedCount(JsonObject entry) {
        if (!entry.has("functions") || !entry.get("functions").isJsonArray()) return 1;
        for (JsonElement f : entry.getAsJsonArray("functions")) {
            if (!f.isJsonObject()) continue;
            JsonObject fn = f.getAsJsonObject();
            String name = fn.has("function") ? fn.get("function").getAsString() : "";
            if (name.endsWith("set_count") && fn.has("count") && fn.get("count").isJsonPrimitive()) {
                return Math.max(1, fn.get("count").getAsInt());
            }
        }
        return 1;
    }

    private static String json(Component c) {
        return Component.Serializer.toJson(c);
    }
}
