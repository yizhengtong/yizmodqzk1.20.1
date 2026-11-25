package net.minecraft.client.yiz.anim.editor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.yiz.anim.AnimClip;
import net.minecraft.client.yiz.anim.BoneTransform;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 第一人称姿态导入：和 1.21.1 版模组一样的做法——
 * 在 Blockbench 里用 {@code firstperson_righthand}/{@code firstperson_lefthand} display transform
 * 摆好 a1/a2/a3/a4 等姿态（每个文件一个姿态），存成 .json 或 .bbmodel 放进
 * {@code config/yizmodqzk/fp_poses/}，再在游戏里给每个文件设定时间帧。
 *
 * <p>读取的是文件里的 {@code display} 段（旋转角度 / 1/16 格平移 / 缩放），
 * 转成 {@link BoneTransform}（平移换算成格）写进 {@code fp_right_arm}/{@code fp_left_arm}
 * 的关键帧。导入是<b>覆盖</b>这两根骨骼原有的关键帧。</p>
 */
public final class FpPoseImporter {

    /** 导入列表里的一行：文件 + 时间（秒）+ 是否启用。 */
    public record Entry(Path file, float time, boolean enabled) {}

    private static final Gson GSON = new Gson();

    private FpPoseImporter() {}

    /** 姿态文件目录。 */
    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("yizmodqzk").resolve("fp_poses");
    }

    /** 确保目录存在，并写一份使用说明。 */
    public static void ensureDir() {
        try {
            Files.createDirectories(dir());
            Path readme = dir().resolve("README.txt");
            if (!Files.isRegularFile(readme)) {
                Files.writeString(readme, """
                        把 Blockbench 里摆好的 a1/a2/a3/a4 文件放到这个目录（.json 或 .bbmodel）。
                        每个文件需要带 display.firstperson_righthand（左手用 firstperson_lefthand）。
                        然后在游戏里按 K 打开动画编辑器 →「导入FP姿态」→ 给每个文件填时间帧（秒）。
                        导入会覆盖当前动画 fp_right_arm / fp_left_arm 的关键帧。
                        """);
            }
        } catch (Exception ignored) {
        }
    }

    /** 目录里的姿态文件（按文件名排序）。 */
    public static List<Path> listFiles() {
        ensureDir();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir())) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".json") || name.endsWith(".bbmodel");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(files::add);
        } catch (Exception ignored) {
        }
        return files;
    }

    /** 文件显示名（去扩展名）。 */
    public static String displayName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 读取一个文件里的第一人称 display transform；没有则返回 null。
     *
     * @param leftHand true 读 {@code firstperson_lefthand}，false 读 {@code firstperson_righthand}
     */
    public static BoneTransform load(Path file, boolean leftHand) {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("display") || !root.get("display").isJsonObject()) {
                return null;
            }
            JsonObject display = root.getAsJsonObject("display");
            String key = leftHand ? "firstperson_lefthand" : "firstperson_righthand";
            if (!display.has(key) || !display.get(key).isJsonObject()) {
                return null;
            }
            JsonObject transform = display.getAsJsonObject(key);
            float[] rotation = read3(transform, "rotation", 0F);
            float[] translation = read3(transform, "translation", 0F);
            float[] scale = read3(transform, "scale", 1F);
            return new BoneTransform(
                    wrapDegrees(rotation[0]), wrapDegrees(rotation[1]), wrapDegrees(rotation[2]),
                    translation[0] / 16F, translation[1] / 16F, translation[2] / 16F,
                    scale[0], scale[1], scale[2]);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 把选中的姿态按时间导入当前动画（覆盖 fp_right_arm / fp_left_arm）。
     * 文件里同时有左右手 display 就两边都写。
     *
     * @return 写入的关键帧数量
     */
    public static int importTo(AnimClip clip, List<Entry> entries) {
        if (clip == null) {
            return 0;
        }
        clip.clearKeyframes("fp_right_arm");
        clip.clearKeyframes("fp_left_arm");
        int count = 0;
        for (Entry entry : entries) {
            if (!entry.enabled()) {
                continue;
            }
            float time = Math.max(0F, Math.min(clip.duration(), entry.time()));
            BoneTransform right = load(entry.file(), false);
            if (right != null) {
                clip.setKeyframe("fp_right_arm", time, right);
                count++;
            }
            BoneTransform left = load(entry.file(), true);
            if (left != null) {
                clip.setKeyframe("fp_left_arm", time, left);
                count++;
            }
        }
        if (count > 0) {
            // 只有真正导入过姿态才启用第一人称；普通第三人称编辑默认保持原版
            clip.setFirstPerson(true);
        }
        return count;
    }

    private static float[] read3(JsonObject object, String key, float fallback) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return new float[]{fallback, fallback, fallback};
        }
        JsonArray array = object.getAsJsonArray(key);
        if (array.size() < 3) {
            return new float[]{fallback, fallback, fallback};
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static float wrapDegrees(float value) {
        float wrapped = value % 360F;
        if (wrapped >= 180F) {
            wrapped -= 360F;
        }
        if (wrapped < -180F) {
            wrapped += 360F;
        }
        return wrapped;
    }
}
