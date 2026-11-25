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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动画系统配置（{@code config/yizmodqzk/animation_config.json}），热加载。
 *
 * <p>核心是 <b>物品动画配置表</b>：每个物品可以分别配置第三人称和第一人称要播放的动画，
 * 默认是空表（{@code "items": {}}）——<b>所有物品默认都没有动画</b>，
 * 全部由玩家自己往表里加。第一人称没有配置时保持原版手部动画。</p>
 *
 * <pre>{@code
 * {
 *   "min_duration_ticks": 3,
 *   "trigger": "attack",              // attack / use / both
 *   "items": {
 *     "yizxianmod:jian_1": {
 *       "third_person": ["11_restored", "22_restored", "33_restored"],
 *       "first_person": ["jian_1_fp_1", "jian_1_fp_2"]
 *     },
 *     "minecraft:iron_sword": {
 *       "first_person": ["fp_slash"]
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>{@code third_person}/{@code first_person} 都是动画名数组，按顺序播放（每次攻击取下一段）。
 * 任意一边留空或省略，就表示该物品在这一边不播放自定义动画（走原版）。</p>
 */
public final class AnimConfig {

    /** 单个物品的动画配置。 */
    public static final class ItemAnim {
        private final List<String> thirdPerson;
        private final List<String> firstPerson;

        public ItemAnim(List<String> thirdPerson, List<String> firstPerson) {
            this.thirdPerson = List.copyOf(thirdPerson);
            this.firstPerson = List.copyOf(firstPerson);
        }

        public List<String> thirdPerson() {
            return this.thirdPerson;
        }

        public List<String> firstPerson() {
            return this.firstPerson;
        }

        public boolean isEmpty() {
            return this.thirdPerson.isEmpty() && this.firstPerson.isEmpty();
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile int minDurationTicks = 3;
    private static volatile String trigger = "attack";
    private static volatile Map<String, ItemAnim> items = Map.of();

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

    /** 物品动画配置表（物品注册名 → 配置）；默认空表。 */
    public static Map<String, ItemAnim> items() {
        return items;
    }

    public static ItemAnim item(String itemId) {
        return itemId == null ? null : items.get(itemId);
    }

    /**
     * 把一段动画绑定到物品（编辑器保存时调用）：追加到 {@code third_person}/{@code first_person}
     * 列表里（去重），之后需要 {@link #save()} + {@code AnimLibrary.reload()}。
     */
    public static synchronized void bind(String itemId, String clipName, boolean thirdPerson, boolean firstPerson) {
        if (itemId == null || itemId.isBlank() || clipName == null || clipName.isBlank()) {
            return;
        }
        Map<String, ItemAnim> table = new LinkedHashMap<>(items);
        ItemAnim old = table.get(itemId);
        List<String> third = new ArrayList<>(old == null ? List.of() : old.thirdPerson());
        List<String> first = new ArrayList<>(old == null ? List.of() : old.firstPerson());
        if (thirdPerson && !third.contains(clipName)) {
            third.add(clipName);
        }
        if (firstPerson && !first.contains(clipName)) {
            first.add(clipName);
        }
        table.put(itemId, new ItemAnim(third, first));
        items = Map.copyOf(table);
    }

    /** 把某段动画从物品的第一人称列表移除（该动画没导入过姿态时，保存会用到这里）。 */
    public static synchronized void removeFirstPerson(String itemId, String clipName) {
        if (itemId == null || clipName == null) {
            return;
        }
        ItemAnim old = items.get(itemId);
        if (old == null || !old.firstPerson().contains(clipName)) {
            return;
        }
        Map<String, ItemAnim> table = new LinkedHashMap<>(items);
        List<String> first = new ArrayList<>(old.firstPerson());
        first.remove(clipName);
        table.put(itemId, new ItemAnim(old.thirdPerson(), first));
        items = Map.copyOf(table);
    }

    /** 用代码/加载结果整体替换配置表（{@code AnimLibrary.reload} 清理失效动画名时用）。 */
    public static synchronized void replaceItems(Map<String, ItemAnim> table) {
        items = Map.copyOf(table);
    }

    /** 从配置表删除一个物品的全部动画绑定（物品选择界面右键删除）。 */
    public static synchronized void removeItem(String itemId) {
        if (itemId == null || !items.containsKey(itemId)) {
            return;
        }
        Map<String, ItemAnim> table = new LinkedHashMap<>(items);
        table.remove(itemId);
        items = Map.copyOf(table);
    }

    public static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            items = Map.of();
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
            Map<String, ItemAnim> table = new LinkedHashMap<>();
            if (root.has("items") && root.get("items").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("items").entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject object = entry.getValue().getAsJsonObject();
                    List<String> thirdPerson = readList(object, "third_person");
                    List<String> firstPerson = readList(object, "first_person");
                    if (!thirdPerson.isEmpty() || !firstPerson.isEmpty()) {
                        table.put(entry.getKey(), new ItemAnim(thirdPerson, firstPerson));
                    }
                }
            }
            items = Map.copyOf(table);
            if (!root.has("items")) {
                // 旧配置（all_items/weapons/combo）迁移成空表：默认所有物品都不配置动画
                save();
            }
        } catch (Throwable ignored) {
            // 配置坏掉时保留默认值（空表 = 不播自定义动画）
        }
    }

    private static List<String> readList(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            String name = element.getAsString();
            if (name != null && !name.isBlank()) {
                list.add(name.trim());
            }
        }
        return list;
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            JsonObject root = new JsonObject();
            root.addProperty("min_duration_ticks", minDurationTicks);
            root.addProperty("trigger", trigger);
            JsonObject itemsJson = new JsonObject();
            for (Map.Entry<String, ItemAnim> entry : items.entrySet()) {
                ItemAnim anim = entry.getValue();
                if (anim.isEmpty()) {
                    continue;
                }
                JsonObject object = new JsonObject();
                JsonArray third = new JsonArray();
                anim.thirdPerson().forEach(third::add);
                object.add("third_person", third);
                JsonArray first = new JsonArray();
                anim.firstPerson().forEach(first::add);
                object.add("first_person", first);
                itemsJson.add(entry.getKey(), object);
            }
            root.add("items", itemsJson);
            try (Writer writer = Files.newBufferedWriter(file())) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
        }
    }
}
