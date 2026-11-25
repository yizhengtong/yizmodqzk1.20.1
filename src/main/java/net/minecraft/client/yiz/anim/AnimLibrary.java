package net.minecraft.client.yiz.anim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

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
 * 动画库：动画文件、物品动画绑定、持久化（双端共用）。
 *
 * <p>文件位置：</p>
 * <ul>
 *   <li>{@code config/yizmodqzk/animations/<name>.json} — 编辑器保存的动画</li>
 *   <li>{@code config/yizmodqzk/animation_config.json} — 触发方式 + 物品动画配置表</li>
 * </ul>
 *
 * <p>第三人称和第一人称是两张独立的绑定表：配置表里某物品的 {@code third_person}
 * 只驱动第三人称模型，{@code first_person} 只驱动本地第一人称物品；
 * 两边都没有配置的物品一律播放原版动画（默认空表）。</p>
 */
public final class AnimLibrary {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, AnimClip> CLIPS = new LinkedHashMap<>();

    /** 第三人称：物品 → 动画名列表。 */
    private static final Map<String, List<String>> THIRD_PERSON = new LinkedHashMap<>();
    /** 第一人称：物品 → 动画名列表。 */
    private static final Map<String, List<String>> FIRST_PERSON = new LinkedHashMap<>();
    /** 代码登记的绑定（配置里同名以配置为准）。 */
    private static final Map<String, List<String>> CODE_THIRD_PERSON = new LinkedHashMap<>();
    private static final Map<String, List<String>> CODE_FIRST_PERSON = new LinkedHashMap<>();
    private static volatile boolean loaded = false;

    private AnimLibrary() {}

    public static Path animationDir() {
        return FMLPaths.CONFIGDIR.get().resolve("yizmodqzk").resolve("animations");
    }

    public static AnimClip get(String name) {
        return name == null ? null : CLIPS.get(name);
    }

    public static List<String> names() {
        return List.copyOf(CLIPS.keySet());
    }

    public static boolean loaded() {
        return loaded;
    }

    /** 该物品是否有自定义动画（第三人称或第一人称任一）。 */
    public static boolean isAnimWeapon(ItemStack stack) {
        return hasThirdPerson(stack) || hasFirstPerson(stack);
    }

    public static boolean hasThirdPerson(ItemStack stack) {
        return !thirdPersonClips(stack).isEmpty();
    }

    public static boolean hasFirstPerson(ItemStack stack) {
        return !firstPersonClips(stack).isEmpty();
    }

    /** 该物品配置的第三人称动画名列表（默认空）。 */
    public static List<String> thirdPersonClips(ItemStack stack) {
        return boundClips(stack, THIRD_PERSON);
    }

    /** 该物品配置的第一人称动画名列表（默认空）。 */
    public static List<String> firstPersonClips(ItemStack stack) {
        return boundClips(stack, FIRST_PERSON);
    }

    private static List<String> boundClips(ItemStack stack, Map<String, List<String>> table) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return List.of();
        }
        List<String> names = table.get(key.toString());
        return names == null ? List.of() : names;
    }

    /** 第三人称连段动画（每次攻击取下一条，循环）。 */
    public static AnimClip comboClip(ItemStack stack, int comboIndex) {
        return pick(thirdPersonClips(stack), comboIndex);
    }

    /** 第一人称连段动画（本地播放，和第三人称完全独立）。 */
    public static AnimClip firstPersonComboClip(ItemStack stack, int comboIndex) {
        return pick(firstPersonClips(stack), comboIndex);
    }

    private static AnimClip pick(List<String> names, int comboIndex) {
        if (names.isEmpty()) {
            return null;
        }
        AnimClip clip = CLIPS.get(names.get(Math.floorMod(comboIndex, names.size())));
        if (clip != null) {
            return clip;
        }
        for (String name : names) {
            AnimClip fallback = CLIPS.get(name);
            if (fallback != null) {
                return fallback;
            }
        }
        return null;
    }

    /** 用代码登记第三人称武器绑定（依赖模组调用；配置里同名以配置为准）。 */
    public static void bindWeapon(String itemId, List<String> clipNames) {
        if (itemId == null || itemId.isBlank() || clipNames == null || clipNames.isEmpty()) {
            return;
        }
        CODE_THIRD_PERSON.put(itemId, List.copyOf(clipNames));
        THIRD_PERSON.put(itemId, List.copyOf(clipNames));
    }

    /** 用代码登记第一人称绑定（仅本地播放）。 */
    public static void bindFirstPerson(String itemId, List<String> clipNames) {
        if (itemId == null || itemId.isBlank() || clipNames == null || clipNames.isEmpty()) {
            return;
        }
        CODE_FIRST_PERSON.put(itemId, List.copyOf(clipNames));
        FIRST_PERSON.put(itemId, List.copyOf(clipNames));
    }

    /** 保存动画到磁盘（编辑器保存/热加载都会用）。 */
    public static void save(AnimClip clip) {
        if (clip == null) {
            return;
        }
        try {
            Files.createDirectories(animationDir());
            Path file = fileOf(clip.name());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(clip.toJson(), writer);
            }
            CLIPS.put(clip.name(), clip);
        } catch (IOException e) {
            throw new RuntimeException("保存动画失败: " + clip.name(), e);
        }
    }

    /** 动画名对应的文件路径（文件名做了安全化）。 */
    public static Path fileOf(String name) {
        return animationDir().resolve(safeName(name) + ".json");
    }

    public static void reload() {
        CLIPS.clear();
        THIRD_PERSON.clear();
        FIRST_PERSON.clear();
        AnimConfig.load();
        loadFiles();

        // 配置表：每个物品分别登记第三人称 / 第一人称。
        // 同时清掉引用了不存在动画文件的名字——否则 comboClip 的 fallback 会把它们都映射到
        // 列表里第一个存在的动画，出现「第 1 个动画重复播放好几次」的问题。
        Map<String, AnimConfig.ItemAnim> pruned = new LinkedHashMap<>();
        for (Map.Entry<String, AnimConfig.ItemAnim> entry : AnimConfig.items().entrySet()) {
            List<String> third = existingClips(entry.getValue().thirdPerson());
            List<String> first = existingClips(entry.getValue().firstPerson());
            if (third.isEmpty() && first.isEmpty()) {
                continue;
            }
            pruned.put(entry.getKey(), new AnimConfig.ItemAnim(third, first));
            if (!third.isEmpty()) {
                THIRD_PERSON.put(entry.getKey(), third);
            }
            if (!first.isEmpty()) {
                FIRST_PERSON.put(entry.getKey(), first);
            }
        }
        AnimConfig.replaceItems(pruned);
        AnimConfig.save();

        CODE_THIRD_PERSON.forEach((itemId, names) -> {
            List<String> valid = existingClips(names);
            if (!valid.isEmpty()) {
                THIRD_PERSON.putIfAbsent(itemId, valid);
            }
        });
        CODE_FIRST_PERSON.forEach((itemId, names) -> {
            List<String> valid = existingClips(names);
            if (!valid.isEmpty()) {
                FIRST_PERSON.putIfAbsent(itemId, valid);
            }
        });
        loaded = true;
        net.minecraft.client.yiz.tizMod.LOGGER.info(
                "[Anim] 动画库加载：{} 段动画，第三人称绑定 {} 个物品，第一人称绑定 {} 个物品",
                CLIPS.size(), THIRD_PERSON.size(), FIRST_PERSON.size());
    }

    /** 只保留实际存在动画文件的名字。 */
    private static List<String> existingClips(List<String> names) {
        List<String> valid = new ArrayList<>();
        for (String name : names) {
            if (name != null && CLIPS.containsKey(name)) {
                valid.add(name);
            }
        }
        return valid;
    }

    private static void loadFiles() {
        Path dir = animationDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try (Reader reader = Files.newBufferedReader(path)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    AnimClip clip = AnimClip.fromJson(root);
                    CLIPS.put(clip.name(), clip);
                } catch (Throwable ignored) {
                    // 单个坏文件不影响其它动画
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String safeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
