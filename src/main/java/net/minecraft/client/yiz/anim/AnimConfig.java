package net.minecraft.client.yiz.anim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 动画系统配置（{@code config/yizmodqzk/animation_config.json}），热加载。
 *
 * <pre>{@code
 * {
 *   "min_duration_ticks": 3,
 *   "trigger": "attack",          // attack / use / both
 *   "all_items": true,            // 所有手持物品都触发（白名单后续再配）
 *   "weapons": [],                // 指定物品（注册名）
 *   "combo": ["anim_1", "anim_2"] // 连段顺序
 * }
 * }</pre>
 */
public final class AnimConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile int minDurationTicks = 3;
    private static volatile String trigger = "attack";
    private static volatile boolean allItems = true;
    private static volatile List<String> weapons = List.of();
    private static volatile List<String> combo = List.of();

    private AnimConfig() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("yizmodqzk").resolve("animation_config.json");
    }

    public static int minDurationTicks() {
        return minDurationTicks;
    }

    public static String trigger() {
        return trigger;
    }

    public static boolean triggersOnAttack() {
        return "attack".equals(trigger) || "both".equals(trigger);
    }

    public static boolean triggersOnUse() {
        return "use".equals(trigger) || "both".equals(trigger);
    }

    public static boolean allItems() {
        return allItems;
    }

    public static List<String> weapons() {
        return weapons;
    }

    public static List<String> combo() {
        return combo;
    }

    public static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("min_duration_ticks")) {
                minDurationTicks = Math.max(1, root.get("min_duration_ticks").getAsInt());
            }
            if (root.has("trigger")) {
                String value = root.get("trigger").getAsString();
                if ("attack".equals(value) || "use".equals(value) || "both".equals(value)) {
                    trigger = value;
                }
            }
            if (root.has("all_items")) {
                allItems = root.get("all_items").getAsBoolean();
            }
            if (root.has("weapons")) {
                List<String> list = new ArrayList<>();
                for (JsonElement element : root.getAsJsonArray("weapons")) {
                    list.add(element.getAsString());
                }
                weapons = List.copyOf(list);
            }
            if (root.has("combo")) {
                List<String> list = new ArrayList<>();
                for (JsonElement element : root.getAsJsonArray("combo")) {
                    list.add(element.getAsString());
                }
                combo = List.copyOf(list);
            }
        } catch (Throwable ignored) {
            // 配置坏掉时保留默认值
        }
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            JsonObject root = new JsonObject();
            root.addProperty("min_duration_ticks", minDurationTicks);
            root.addProperty("trigger", trigger);
            root.addProperty("all_items", allItems);
            JsonArray weaponsJson = new JsonArray();
            weapons.forEach(weaponsJson::add);
            root.add("weapons", weaponsJson);
            JsonArray comboJson = new JsonArray();
            combo.forEach(comboJson::add);
            root.add("combo", comboJson);
            try (Writer writer = Files.newBufferedWriter(file())) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
        }
    }
}
