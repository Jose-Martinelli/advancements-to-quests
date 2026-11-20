package net.advquests.generator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.Criterion;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Transforma advancements do tipo "obter/craftar item" em tasks de item do FTB Quests.
 * <p>
 * A logica do advancement e "E de grupos OU" ({@code requirements}); dentro de um criterio
 * {@code inventory_changed}, cada predicado e obrigatorio e cada predicado aceita qualquer um dos seus itens.
 * Isso e reduzido a "slots": cada slot precisa de UM dos seus itens alternativos.
 * <ul>
 *   <li>Todos os slots com um unico item -> {@link Mode#ALL}: uma task obrigatoria por item.</li>
 *   <li>Um unico slot com varias alternativas -> {@link Mode#ANY}: tasks opcionais (o FTB Quests conclui a
 *       quest quando qualquer uma e feita, se todas as tasks forem opcionais).</li>
 *   <li>Qualquer outra coisa (condicoes de NBT, encantamento, combinacoes mistas...) -> null: a quest
 *       continua usando a task de advancement, que e sempre fiel.</li>
 * </ul>
 */
public final class CriteriaItemResolver {
    private static final String INVENTORY_CHANGED = "minecraft:inventory_changed";
    private static final String RECIPE_CRAFTED = "minecraft:recipe_crafted";

    private final MinecraftServer server;
    private final int maxItems;

    public enum Mode { ALL, ANY }

    /** Uma task de item: item, quantidade e se so conta itens craftados. */
    public record ItemReq(Item item, int count, boolean onlyFromCrafting) {
    }

    public record Plan(Mode mode, List<ItemReq> items) {
    }

    /** Um slot: precisa de UM item entre as alternativas. */
    private record Slot(Set<Item> alternatives, int count, boolean crafted) {
    }

    public CriteriaItemResolver(MinecraftServer server, int maxItems) {
        this.server = server;
        this.maxItems = maxItems;
    }

    /** Plano de tasks de item, ou null se o advancement nao puder ser representado fielmente por itens. */
    public Plan resolve(Advancement adv) {
        Map<String, Criterion> criteria = adv.getCriteria();
        String[][] requirements = adv.getRequirements();
        if (criteria.isEmpty() || requirements == null || requirements.length == 0) return null;

        List<Slot> slots = new ArrayList<>();
        for (String[] group : requirements) {
            if (group.length == 0) return null;
            if (group.length == 1) {
                List<Slot> s = slotsOf(criteria.get(group[0]));
                if (s == null || s.isEmpty()) return null;
                slots.addAll(s);
            } else {
                // Grupo OU entre criterios: cada criterio precisa ser um slot simples; viram um slot so.
                Set<Item> alts = new LinkedHashSet<>();
                int count = -1;
                Boolean crafted = null;
                for (String name : group) {
                    List<Slot> s = slotsOf(criteria.get(name));
                    if (s == null || s.size() != 1) return null;
                    Slot one = s.get(0);
                    if (count != -1 && count != one.count()) return null;
                    if (crafted != null && crafted != one.crafted()) return null;
                    count = one.count();
                    crafted = one.crafted();
                    alts.addAll(one.alternatives());
                }
                slots.add(new Slot(alts, count, crafted));
            }
        }

        if (slots.stream().allMatch(s -> s.alternatives().size() == 1)) {
            // E: um item obrigatorio por slot (junta repetidos pela maior quantidade)
            Map<Item, ItemReq> merged = new LinkedHashMap<>();
            for (Slot s : slots) {
                Item item = s.alternatives().iterator().next();
                ItemReq prev = merged.get(item);
                if (prev != null && prev.onlyFromCrafting() != s.crafted()) return null;
                int count = prev == null ? s.count() : Math.max(s.count(), prev.count());
                merged.put(item, new ItemReq(item, count, s.crafted()));
            }
            if (merged.size() > maxItems) return null;
            return new Plan(Mode.ALL, List.copyOf(merged.values()));
        }

        if (slots.size() == 1) {
            // OU: qualquer um dos itens
            Slot s = slots.get(0);
            if (s.alternatives().size() > maxItems) return null;
            List<ItemReq> reqs = new ArrayList<>();
            for (Item item : s.alternatives()) reqs.add(new ItemReq(item, s.count(), s.crafted()));
            return new Plan(Mode.ANY, reqs);
        }
        return null; // mistura de E com OU: o FTB Quests nao representa isso numa quest so
    }

    private List<Slot> slotsOf(Criterion criterion) {
        if (criterion == null) return null;
        JsonObject json;
        try {
            JsonElement serialized = criterion.serializeToJson();
            if (serialized == null || !serialized.isJsonObject()) return null;
            json = serialized.getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
        String trigger = json.has("trigger") ? json.get("trigger").getAsString() : "";
        JsonObject cond = json.has("conditions") && json.get("conditions").isJsonObject()
                ? json.getAsJsonObject("conditions") : new JsonObject();

        if (trigger.equals(INVENTORY_CHANGED)) return inventorySlots(cond);
        if (trigger.equals(RECIPE_CRAFTED)) return craftedSlots(cond);
        return null;
    }

    private List<Slot> inventorySlots(JsonObject cond) {
        for (Map.Entry<String, JsonElement> e : cond.entrySet()) {
            String k = e.getKey();
            if (k.equals("items")) continue;
            // campos vazios/null sao inofensivos; qualquer outra condicao nao da para representar
            if (isEmpty(e.getValue())) continue;
            return null;
        }
        if (!cond.has("items") || !cond.get("items").isJsonArray()) return null;
        List<Slot> slots = new ArrayList<>();
        for (JsonElement p : cond.getAsJsonArray("items")) {
            if (!p.isJsonObject()) return null;
            Slot s = predicateSlot(p.getAsJsonObject());
            if (s == null) return null;
            slots.add(s);
        }
        return slots;
    }

    private List<Slot> craftedSlots(JsonObject cond) {
        for (Map.Entry<String, JsonElement> e : cond.entrySet()) {
            String k = e.getKey();
            if (k.equals("recipe_id")) continue;
            if (isEmpty(e.getValue())) continue;
            return null;
        }
        if (!cond.has("recipe_id")) return null;
        ItemStack result = server.getRecipeManager().byKey(new ResourceLocation(cond.get("recipe_id").getAsString()))
                .map(r -> r.getResultItem(server.registryAccess()))
                .orElse(ItemStack.EMPTY);
        if (result.isEmpty()) return null;
        Set<Item> one = new LinkedHashSet<>();
        one.add(result.getItem());
        return List.of(new Slot(one, 1, true));
    }

    /** Predicado de item: so "items", "tag" e "count" sao representaveis. */
    private Slot predicateSlot(JsonObject pred) {
        Set<Item> alts = new LinkedHashSet<>();
        int count = 1;
        for (Map.Entry<String, JsonElement> e : pred.entrySet()) {
            // O vanilla grava TODOS os campos do predicado, com null quando nao sao usados
            // (ex.: "count": null, "durability": null, "nbt": null). Esses sao ignorados.
            if (isEmpty(e.getValue())) continue;
            switch (e.getKey()) {
                case "items" -> {
                    if (!e.getValue().isJsonArray()) return null;
                    for (JsonElement id : e.getValue().getAsJsonArray()) {
                        Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(id.getAsString()));
                        if (item == Items.AIR) return null;
                        alts.add(item);
                    }
                }
                case "tag" -> {
                    TagKey<Item> tag = TagKey.create(Registries.ITEM, new ResourceLocation(e.getValue().getAsString()));
                    Optional<HolderSet.Named<Item>> set = BuiltInRegistries.ITEM.getTag(tag);
                    if (set.isEmpty()) return null;
                    set.get().stream().map(Holder::value).forEach(alts::add);
                }
                case "count" -> {
                    Integer c = minCount(e.getValue());
                    if (c == null) return null;
                    count = c;
                }
                default -> {
                    return null; // nbt, durability, enchantments, potion...
                }
            }
        }
        return alts.isEmpty() ? null : new Slot(alts, count, false);
    }

    /** Aceita contagem fixa ou so com minimo; maximo nao e representavel. */
    private static Integer minCount(JsonElement c) {
        try {
            if (c.isJsonPrimitive()) return Math.max(1, c.getAsInt());
            if (c.isJsonObject()) {
                JsonObject o = c.getAsJsonObject();
                if (o.has("max")) return null;
                return o.has("min") ? Math.max(1, o.get("min").getAsInt()) : 1;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isEmpty(JsonElement el) {
        if (el == null || el.isJsonNull()) return true;
        if (el.isJsonObject()) {
            return el.getAsJsonObject().entrySet().stream().allMatch(e -> isEmpty(e.getValue()));
        }
        if (el.isJsonArray()) return el.getAsJsonArray().isEmpty();
        return false;
    }
}
