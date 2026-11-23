package net.minecraft.client.yiz.anim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.tags.ItemTags;
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
 * 动画库：动画文件、武器绑定、持久化（双端共用）。
 *
 * <p>文件位置：</p>
 * <ul>
 *   <li>{@code config/yizmodqzk/animations/<name>.json} — 编辑器保存的动画</li>
 *   <li>{@code config/yizmodqzk/animation_config.json} — 触发方式/连段/最小时长</li>
 * </ul>
 */
public final class AnimLibrary {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, AnimClip> CLIPS = new LinkedHashMap<>();
    private static final Map<String, List<String>> BINDINGS = new LinkedHashMap<>();
    private static final Map<String, List<String>> CODE_BINDINGS = new LinkedHashMap<>();
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

    /** 该物品是否触发动画。 */
    public static boolean isAnimWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key != null && BINDINGS.containsKey(key.toString())) {
            return true;
        }
        if (AnimConfig.allItems()) {
            return true;
        }
        for (String weapon : AnimConfig.weapons()) {
            if (key != null && weapon.equals(key.toString())) {
                return true;
            }
        }
        return false;
    }

    /** 该武器的连段动画名列表；未绑定 → 配置 combo；再没有 → 全部动画。 */
    public static List<String> clipsFor(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key != null) {
                List<String> names = BINDINGS.get(key.toString());
                if (names != null && !names.isEmpty()) {
                    return names;
                }
            }
        }
        if (!AnimConfig.combo().isEmpty()) {
            return AnimConfig.combo();
        }
        return new ArrayList<>(CLIPS.keySet());
    }

    public static AnimClip comboClip(ItemStack stack, int comboIndex) {
        List<String> names = clipsFor(stack);
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

    /** 用代码登记武器绑定（依赖模组调用；配置里同名以配置为准）。 */
    public static void bindWeapon(String itemId, List<String> clipNames) {
        if (itemId == null || itemId.isBlank() || clipNames == null || clipNames.isEmpty()) {
            return;
        }
        CODE_BINDINGS.put(itemId, List.copyOf(clipNames));
        BINDINGS.put(itemId, List.copyOf(clipNames));
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
        BINDINGS.clear();
        AnimConfig.load();
        loadFiles();
        // 配置 weapons → combo
        if (!AnimConfig.combo().isEmpty()) {
            for (String weapon : AnimConfig.weapons()) {
                BINDINGS.put(weapon, AnimConfig.combo());
            }
        }
        CODE_BINDINGS.forEach(BINDINGS::putIfAbsent);
        loaded = true;
        net.minecraft.client.yiz.tizMod.LOGGER.info(
                "[Anim] 动画库加载：{} 段动画，{} 个武器绑定，combo={}", CLIPS.size(), BINDINGS.size(), AnimConfig.combo());
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
