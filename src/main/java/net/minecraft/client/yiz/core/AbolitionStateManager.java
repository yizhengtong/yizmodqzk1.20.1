package net.minecraft.client.yiz.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局废除状态持久化（1.20.1 移植版，路径改用 FMLPaths.CONFIGDIR）。
 *
 * <p>存 {@code config/yizmodqzk/abolish.json}：已废除的物品 ID 集合 + 护甲废除开关。
 * 属于全局创作偏好（跨世界保留），第三方 mod 移除后条目保留，重装自动恢复。</p>
 */
public final class AbolitionStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("AbolitionState");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "yizmodqzk/abolish.json";

    private static final Set<ResourceLocation> ABOLISHED_ITEMS = ConcurrentHashMap.newKeySet();
    private static volatile boolean ARMOR_ABOLISHED = false;
    private static volatile boolean loaded = false;

    private AbolitionStateManager() {}

    public static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            if (!Files.exists(p)) return;
            Map<String, Object> data = GSON.fromJson(Files.readString(p), Map.class);
            if (data == null) return;
            if (data.get("abolished") instanceof java.util.List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) {
                        ResourceLocation id = ResourceLocation.tryParse(s);
                        if (id != null) ABOLISHED_ITEMS.add(id);
                    }
                }
            }
            if (data.get("armor") instanceof Boolean b) ARMOR_ABOLISHED = b;
            LOGGER.info("Loaded abolition state: {} items", ABOLISHED_ITEMS.size());
        } catch (Exception ignored) {}
    }

    public static void save() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            Files.createDirectories(p.getParent());
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("abolished", ABOLISHED_ITEMS.stream().map(ResourceLocation::toString).toList());
            data.put("armor", ARMOR_ABOLISHED);
            Files.writeString(p, GSON.toJson(data));
        } catch (Exception ignored) {}
    }

    public static void abolishItem(ResourceLocation itemId) {
        load();
        ABOLISHED_ITEMS.add(itemId);
        save();
    }

    public static void restoreItem(ResourceLocation itemId) {
        load();
        ABOLISHED_ITEMS.remove(itemId);
        save();
    }

    public static boolean isItemAbolished(ResourceLocation itemId) {
        load();
        return ABOLISHED_ITEMS.contains(itemId);
    }

    public static Set<ResourceLocation> getAbolishedItems() {
        load();
        return ABOLISHED_ITEMS;
    }

    public static void setArmorAbolished(boolean abolished) {
        load();
        ARMOR_ABOLISHED = abolished;
        save();
    }

    public static boolean isArmorAbolished() {
        load();
        return ARMOR_ABOLISHED;
    }
}
