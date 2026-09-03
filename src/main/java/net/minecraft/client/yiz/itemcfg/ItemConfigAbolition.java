package net.minecraft.client.yiz.itemcfg;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.yiz.core.VTableReplace;
import net.minecraft.client.yiz.tool.abolish.CurioMethodsDonor;
import net.minecraft.client.yiz.tool.abolish.ItemAbolitionHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局废除层（VTable 覆写，全服生效）。
 *
 * <p>对物品类的 Item 方法入口做 VTable 覆写回基类空实现，专用于"每 tick/持续施加、
 * per-player 无法切断"的功能（inventoryTick / onEquip / onUnequip 等）。
 * 状态持久化到 {@code config/yizmodqzk/abolish.json}，按注册名索引，重装恢复。</p>
 *
 * <p>注意：VTable 覆写偏全局且不可精确逆（JIT 残余），恢复=从配置移除后重启生效。</p>
 */
public final class ItemConfigAbolition {

    private static final String FILE_NAME = "yizmodqzk/abolish.json";
    /** itemId → 已全局废除的 feature id 集合。 */
    private static final Map<ResourceLocation, Set<String>> ABOLISHED = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private ItemConfigAbolition() {}

    // ══════════════════════════════════════════════════════════
    //  生命周期
    // ══════════════════════════════════════════════════════════

    /** 启动加载：读 abolish.json 并对已废除项执行 VTable 覆写。 */
    public static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            JsonObject items = root.getAsJsonObject("items");
            if (items == null) return;
            for (String key : items.keySet()) {
                ResourceLocation id = ResourceLocation.tryParse(key);
                if (id == null) continue;
                Set<String> feats = ConcurrentHashMap.newKeySet();
                items.getAsJsonArray(key).forEach(e -> feats.add(e.getAsString()));
                ABOLISHED.put(id, feats);
            }
        } catch (Exception ignored) {}
        applyAll();
    }

    public static void save() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            Files.createDirectories(p.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("_version", 1);
            JsonObject items = new JsonObject();
            ABOLISHED.forEach((id, feats) -> {
                var arr = new com.google.gson.JsonArray();
                feats.forEach(arr::add);
                items.add(id.toString(), arr);
            });
            root.add("items", items);
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════════════════════
    //  废除 / 恢复
    // ══════════════════════════════════════════════════════════

    /** 全局废除某物品某结构功能（VTable 覆写对应 Item 方法）。 */
    public static void abolish(ResourceLocation itemId, String feature) {
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        FeatureType ft = FeatureType.fromId(feature);
        if (item == null || ft == null) return;
        if (isCurioMethod(ft) && !CuriosBridge.isLoaded()) return;
        if (!VTableReplace.isAvailable()) return;
        if (!VTableReplace.isDonorsInitialized()) VTableReplace.initDonors();

        Class<?> source = isCurioMethod(ft) ? CurioMethodsDonor.class : Item.class;
        for (String[] m : methodOf(ft)) {
            VTableReplace.replaceMethodFromSource(item.getClass(), m[0], m[1], source);
        }
        ABOLISHED.computeIfAbsent(itemId, k -> ConcurrentHashMap.newKeySet()).add(feature);
        save();
    }

    /** 取消全局废除（移除配置；VTable 入口改动重启后不应用即恢复）。 */
    public static void restore(ResourceLocation itemId, String feature) {
        Set<String> feats = ABOLISHED.get(itemId);
        if (feats != null) {
            feats.remove(feature);
            if (feats.isEmpty()) ABOLISHED.remove(itemId);
            save();
        }
    }

    public static boolean isAbolished(ResourceLocation itemId, String feature) {
        return ABOLISHED.getOrDefault(itemId, Set.of()).contains(feature);
    }

    /** 对配置中所有废除项执行 VTable 覆写（启动加载时调用）。 */
    public static void applyAll() {
        if (!VTableReplace.isAvailable()) return;
        if (!VTableReplace.isDonorsInitialized()) VTableReplace.initDonors();
        ABOLISHED.forEach((itemId, feats) -> {
            Item item = ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null) return;
            for (String f : feats) {
                FeatureType ft = FeatureType.fromId(f);
                if (ft == null) continue;
                if (isCurioMethod(ft) && !CuriosBridge.isLoaded()) continue;
                Class<?> source = isCurioMethod(ft) ? CurioMethodsDonor.class : Item.class;
                for (String[] m : methodOf(ft)) {
                    VTableReplace.replaceMethodFromSource(item.getClass(), m[0], m[1], source);
                }
            }
        });
    }

    // ══════════════════════════════════════════════════════════
    //  功能 → Item 方法映射
    // ══════════════════════════════════════════════════════════

    private static String[][] methodOf(FeatureType ft) {
        switch (ft) {
            case RIGHT_CLICK:      return new String[][]{{"use", ItemAbolitionHelper.DESC_USE}};
            case PLACE:            return new String[][]{{"useOn", ItemAbolitionHelper.DESC_USE_ON}};
            case ATTACK_EFFECT:    return new String[][]{{"hurtEnemy", ItemAbolitionHelper.DESC_HURT_ENEMY}};
            case FOOD:             return new String[][]{{"use", ItemAbolitionHelper.DESC_USE}};
            case CHARGE_RELEASE:   return new String[][]{{"releaseUsing", ItemAbolitionHelper.DESC_RELEASE_USING}};
            case INVENTORY_TICK:   return new String[][]{{"inventoryTick", ItemAbolitionHelper.DESC_INVENTORY_TICK}};
            case ARMOR_TICK:       return new String[][]{{"onEquip", ItemAbolitionHelper.DESC_ON_EQUIP},
                                                         {"onUnequip", ItemAbolitionHelper.DESC_ON_UNEQUIP}};
            // Curios 饰品方法（VTable 覆写回空，source=CurioMethodsDonor）
            case CURIO_TICK:       return new String[][]{{"curioTick",
                "(Ltop/theillusivec4/curios/api/SlotContext;Lnet/minecraft/world/item/ItemStack;)V"}};
            case CURIO_ON_EQUIP:   return new String[][]{{"onEquip",
                "(Ltop/theillusivec4/curios/api/SlotContext;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V"}};
            case CURIO_ON_UNEQUIP: return new String[][]{{"onUnequip",
                "(Ltop/theillusivec4/curios/api/SlotContext;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V"}};
            case CURIO_BREAK:      return new String[][]{{"curioBreak",
                "(Ltop/theillusivec4/curios/api/SlotContext;Lnet/minecraft/world/item/ItemStack;)V"}};
            default:               return new String[0][];
        }
    }

    /** Curios 饰品方法（VTable source 用 CurioMethodsDonor，且需 Curios 已加载）。 */
    private static boolean isCurioMethod(FeatureType ft) {
        return ft == FeatureType.CURIO_TICK || ft == FeatureType.CURIO_ON_EQUIP
            || ft == FeatureType.CURIO_ON_UNEQUIP || ft == FeatureType.CURIO_BREAK;
    }

    /** 是否为"全局废除型"功能：GUI 状态读 abolish.json，需 op 配置（VTable 全局生效，非 per-player）。 */
    public static boolean isAbolitionFeature(FeatureType ft) {
        return isCurioMethod(ft) || ft == FeatureType.ARMOR_TICK;
    }
}
