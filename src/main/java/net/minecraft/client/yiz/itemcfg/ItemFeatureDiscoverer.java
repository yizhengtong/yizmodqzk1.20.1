package net.minecraft.client.yiz.itemcfg;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发现层：遍历注册物品，反射分析覆写方法 → FeatureType 列表，缓存 items.json。
 *
 * <p>判定覆写用 getMethod + declaringClass（规避 SRG/Mojmap 名差异），
 * 全程 try-catch 防第三方类反射触发异常。</p>
 */
public final class ItemFeatureDiscoverer {

    private static final String FILE_NAME = "yizmodqzk/items.json";
    private static final ConcurrentHashMap<ResourceLocation, EnumSet<FeatureType>> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private ItemFeatureDiscoverer() {}

    // ══════════════════════════════════════════════════════════
    //  持久化
    // ══════════════════════════════════════════════════════════

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
                EnumSet<FeatureType> set = EnumSet.noneOf(FeatureType.class);
                for (var e : items.getAsJsonArray(key)) {
                    FeatureType ft = FeatureType.fromId(e.getAsString());
                    if (ft != null) set.add(ft);
                }
                CACHE.put(id, set);
            }
        } catch (Exception ignored) {}
    }

    public static void save() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            Files.createDirectories(p.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("_version", 1);
            JsonObject items = new JsonObject();
            CACHE.forEach((id, set) -> {
                JsonArray arr = new JsonArray();
                set.forEach(ft -> arr.add(ft.name()));
                items.add(id.toString(), arr);
            });
            root.add("items", items);
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ignored) {}
    }

    // ══════════════════════════════════════════════════════════
    //  扫描
    // ══════════════════════════════════════════════════════════

    /** 全量扫描（服务端启动时调用一次）。 */
    public static void scanAll() {
        int n = 0;
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) continue;
            CACHE.put(id, analyze(item));
            n++;
        }
        save();
        net.minecraft.client.yiz.tizMod.LOGGER.info("ItemConfig 扫描完成: {} items", n);
    }

    /** 懒扫描单个物品（网络请求缓存 miss 时）。 */
    public static synchronized EnumSet<FeatureType> getOrScan(ResourceLocation id) {
        EnumSet<FeatureType> cached = CACHE.get(id);
        if (cached != null) return cached;
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) return EnumSet.noneOf(FeatureType.class);
        EnumSet<FeatureType> set = analyze(item);
        CACHE.put(id, set);
        return set;
    }

    public static EnumSet<FeatureType> get(ResourceLocation id) {
        EnumSet<FeatureType> s = CACHE.get(id);
        return s == null ? EnumSet.noneOf(FeatureType.class) : s;
    }

    public static boolean has(ResourceLocation id, FeatureType ft) {
        return get(id).contains(ft);
    }

    /** 分析单个物品：结构功能探测 + BlockItem 归入 PLACE。 */
    private static EnumSet<FeatureType> analyze(Item item) {
        EnumSet<FeatureType> set = EnumSet.noneOf(FeatureType.class);
        try {
            Class<?> clazz = item.getClass();
            for (FeatureType ft : FeatureType.values()) {
                if (ft.detected(clazz)) set.add(ft);
            }
            if (item instanceof BlockItem) set.add(FeatureType.PLACE);
            // Curios 饰品：注册了槽位 → 整体禁用 + 通用持续效果开关（curioTick 是统一分发机制）；
            // ICurioItem 方法级覆写 → 精确功能开关（属性/装备/卸下/损坏）
            if (CuriosBridge.isLoaded()) {
                if (CuriosBridge.hasCurioSlots(item)) {
                    set.add(FeatureType.CURIOS_SLOT);
                    set.add(FeatureType.CURIO_TICK);
                }
                set.addAll(CuriosBridge.detectFeatures(item));
            }
        } catch (Throwable ignored) {}
        return set;
    }

    // ══════════════════════════════════════════════════════════
    //  展示辅助
    // ══════════════════════════════════════════════════════════

    /** 物品翻译 key（客户端 Component.translatable 渲染中文）。 */
    public static String itemNameKey(ResourceLocation id) {
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item != null ? item.getDescriptionId() : id.toString();
    }

    /** mod 显示名，找不到回退命名空间。 */
    public static String modName(ResourceLocation id) {
        try {
            String ns = id.getNamespace();
            var mod = ModList.get().getModContainerById(ns);
            return mod.isPresent() ? mod.get().getModInfo().getDisplayName() : ns;
        } catch (Throwable t) {
            return id.getNamespace();
        }
    }
}
