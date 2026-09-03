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
 * <p>per-player（players.json，玩家覆盖）+ global（global.json，管理员默认）。
 * 生效值解析链：{@code players[uuid][item][feature] ?? global[item][feature] ?? false}。</p>
 *
 * <p>feature 用 String 而非 FeatureType——语义功能（adapter 声明）不是枚举成员。</p>
 */
public final class ConfigRegistry {

    private static final String PLAYERS_FILE = "yizmodqzk/players.json";
    private static final String GLOBAL_FILE = "yizmodqzk/global.json";

    /** players[uuid][itemId][featureId] = disabled */
    private static final Map<UUID, Map<ResourceLocation, Map<String, Boolean>>> PLAYERS = new ConcurrentHashMap<>();
    /** global[itemId][featureId] = disabled */
    private static final Map<ResourceLocation, Map<String, Boolean>> GLOBAL = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private ConfigRegistry() {}

    // ══════════════════════════════════════════════════════════
    //  查询
    // ══════════════════════════════════════════════════════════

    /** 生效值解析（玩家覆盖优先）。 */
    public static boolean isDisabled(UUID uuid, ResourceLocation item, String feature) {
        Map<ResourceLocation, Map<String, Boolean>> p = PLAYERS.get(uuid);
        if (p != null) {
            Map<String, Boolean> f = p.get(item);
            if (f != null && f.containsKey(feature)) return f.get(feature);
        }
        Map<String, Boolean> g = GLOBAL.get(item);
        return g != null && Boolean.TRUE.equals(g.get(feature));
    }

    /** 玩家是否有显式覆盖（供 GUI 标注）。 */
    public static boolean hasPlayerOverride(UUID uuid, ResourceLocation item, String feature) {
        Map<ResourceLocation, Map<String, Boolean>> p = PLAYERS.get(uuid);
        if (p == null) return false;
        Map<String, Boolean> f = p.get(item);
        return f != null && f.containsKey(feature);
    }

    // ══════════════════════════════════════════════════════════
    //  修改（写透）
    // ══════════════════════════════════════════════════════════

    public static void setPlayerDisabled(UUID uuid, ResourceLocation item, String feature, boolean disabled) {
        PLAYERS.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(item, k -> new ConcurrentHashMap<>())
                .put(feature, disabled);
        savePlayers();
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
        loadPlayers();
        loadGlobal();
    }

    private static void loadPlayers() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(PLAYERS_FILE);
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            JsonObject players = root.getAsJsonObject("players");
            if (players == null) return;
            for (String uuidStr : players.keySet()) {
                UUID uuid = UUID.fromString(uuidStr);
                Map<ResourceLocation, Map<String, Boolean>> itemMap = new ConcurrentHashMap<>();
                players.getAsJsonObject(uuidStr).entrySet().forEach(e -> {
                    ResourceLocation item = ResourceLocation.tryParse(e.getKey());
                    if (item == null) return;
                    Map<String, Boolean> feats = new ConcurrentHashMap<>();
                    e.getValue().getAsJsonObject().entrySet()
                            .forEach(f -> feats.put(f.getKey(), f.getValue().getAsBoolean()));
                    itemMap.put(item, feats);
                });
                PLAYERS.put(uuid, itemMap);
            }
        } catch (Exception ignored) {}
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

    public static void savePlayers() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(PLAYERS_FILE);
            Files.createDirectories(p.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("_version", 1);
            JsonObject players = new JsonObject();
            PLAYERS.forEach((uuid, itemMap) -> {
                JsonObject items = new JsonObject();
                itemMap.forEach((item, feats) -> {
                    JsonObject fo = new JsonObject();
                    feats.forEach(fo::addProperty);
                    items.add(item.toString(), fo);
                });
                players.add(uuid.toString(), items);
            });
            root.add("players", players);
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(root));
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
