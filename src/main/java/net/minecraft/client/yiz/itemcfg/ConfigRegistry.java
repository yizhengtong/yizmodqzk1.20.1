package net.minecraft.client.yiz.itemcfg;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置层（服务端权威，写透持久化）。
 *
 * <p>per-player（{@code players.json}，玩家覆盖；<b>由 {@link net.minecraft.client.yiz.core.PlayerIdentityStore}
 * 统一持有与写入</b>—— 那里带 UUID 别名与名字历史，离线模式改名不会丢配置）+ global（global.json，管理员默认）。
 * 生效值解析链：{@code players[uuid][item][feature] ?? global[item][feature] ?? false}。</p>
 *
 * <p>feature 用 String 而非 FeatureType—— 语义功能（adapter 声明）不是枚举成员。</p>
 */
public final class ConfigRegistry {

    private static final String GLOBAL_FILE = "yizmodqzk/global.json";

    /** global[itemId][featureId] = disabled */
    private static final Map<ResourceLocation, Map<String, Boolean>> GLOBAL = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private ConfigRegistry() {}

    // ══════════════════════════════════════════════════════════
    //  查询
    // ══════════════════════════════════════════════════════════

    /** 生效值解析（玩家覆盖优先）。 */
    public static boolean isDisabled(UUID uuid, ResourceLocation item, String feature) {
        Boolean v = uuid == null ? null : net.minecraft.client.yiz.core.PlayerIdentityStore
            .itemFeature(uuid, item, feature);
        if (v != null) return v;
        Map<String, Boolean> g = GLOBAL.get(item);
        return g != null && Boolean.TRUE.equals(g.get(feature));
    }

    /** 玩家是否有显式覆盖（供 GUI 标注）。 */
    public static boolean hasPlayerOverride(UUID uuid, ResourceLocation item, String feature) {
        return uuid != null && net.minecraft.client.yiz.core.PlayerIdentityStore
            .itemFeature(uuid, item, feature) != null;
    }

    // ══════════════════════════════════════════════════════════
    //  修改（写透）
    // ══════════════════════════════════════════════════════════

    public static void setPlayerDisabled(UUID uuid, ResourceLocation item, String feature, boolean disabled) {
        net.minecraft.client.yiz.core.PlayerIdentityStore.setItemFeature(uuid, item, feature, disabled);
    }

    public static void setGlobalDisabled(ResourceLocation item, String feature, boolean disabled) {
        GLOBAL.computeIfAbsent(item, k -> new ConcurrentHashMap<>()).put(feature, disabled);
        saveGlobal();
    }

    // ══════════════════════════════════════════════════════════
    //  持久化
    // ══════════════════════════════════════════════════════════

    public static void load() {
        if (loaded) return;
        loaded = true;
        net.minecraft.client.yiz.core.PlayerIdentityStore.load();
        loadGlobal();
    }

    /** 兼容旧调用点：玩家数据由 {@link net.minecraft.client.yiz.core.PlayerIdentityStore} 落盘。 */
    public static void savePlayers() {
        net.minecraft.client.yiz.core.PlayerIdentityStore.save();
    }

    private static void loadGlobal() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(GLOBAL_FILE);
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            JsonObject items = root.getAsJsonObject("items");
            if (items == null) return;
            items.entrySet().forEach(e -> {
                ResourceLocation item = ResourceLocation.tryParse(e.getKey());
                if (item == null) return;
                Map<String, Boolean> feats = new ConcurrentHashMap<>();
                e.getValue().getAsJsonObject().entrySet()
                        .forEach(f -> feats.put(f.getKey(), f.getValue().getAsBoolean()));
                GLOBAL.put(item, feats);
            });
        } catch (Exception ignored) {}
    }

    public static void saveGlobal() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(GLOBAL_FILE);
            Files.createDirectories(p.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("_version", 1);
            JsonObject items = new JsonObject();
            GLOBAL.forEach((item, feats) -> {
                JsonObject fo = new JsonObject();
                feats.forEach(fo::addProperty);
                items.add(item.toString(), fo);
            });
            root.add("items", items);
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ignored) {}
    }
}
