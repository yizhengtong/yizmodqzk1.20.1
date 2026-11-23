package net.minecraft.client.yiz.anim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 动画文件管理：重命名、删除到回收站、从回收站恢复。
 *
 * <p>回收站是 {@code config/yizmodqzk/animations/trash/}，最多保留 10 个文件，
 * 超出时删最旧的；恢复时由玩家输入新名字（回收站里的旧名可能已被占用）。</p>
 */
public final class AnimStorage {

    /** 回收站最多保留数量。 */
    public static final int MAX_TRASH = 10;

    private AnimStorage() {}

    public static Path trashDir() {
        return AnimLibrary.animationDir().resolve("trash");
    }

    /** 当前动画文件名（不含 .json，按名字排序）。 */
    public static List<String> animationFiles() {
        List<String> names = new ArrayList<>();
        Path dir = AnimLibrary.animationDir();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> names.add(stripJson(path.getFileName().toString())));
        } catch (Throwable ignored) {
        }
        names.sort(String::compareTo);
        return names;
    }

    /** 重命名：保存新名字的文件，删除旧文件，重载库。 */
    public static boolean rename(String oldName, String newName) {
        if (oldName == null || newName == null || newName.isBlank() || oldName.equals(newName)) {
            return false;
        }
        AnimClip clip = AnimLibrary.get(oldName);
        if (clip == null) {
            clip = read(oldName);
        }
        if (clip == null) {
            return false;
        }
        AnimClip renamed = clip.copy();
        renamed.setName(newName.trim());
        AnimLibrary.save(renamed);
        deleteQuietly(AnimLibrary.fileOf(oldName));
        AnimLibrary.reload();
        return true;
    }

    /** 删除动画：移动到回收站，超出容量时删最旧。 */
    public static boolean moveToTrash(String name) {
        Path file = AnimLibrary.fileOf(name);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Files.createDirectories(trashDir());
            Path target = trashDir().resolve(name + "_" + System.currentTimeMillis() + ".json");
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            pruneTrash();
            AnimLibrary.reload();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 回收站文件列表（按修改时间从新到旧）。 */
    public static List<Path> trashFiles() {
        List<Path> files = new ArrayList<>();
        Path dir = trashDir();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(files::add);
        } catch (Throwable ignored) {
        }
        files.sort(Comparator.comparingLong((Path path) -> {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (Throwable ignored) {
                return 0L;
            }
        }).reversed());
        return files;
    }

    /** 从回收站恢复，用玩家指定的新名字保存。 */
    public static boolean recover(Path trashFile, String newName) {
        if (trashFile == null || newName == null || newName.isBlank()) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(trashFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            AnimClip clip = AnimClip.fromJson(root);
            clip.setName(newName.trim());
            AnimLibrary.save(clip);
            Files.deleteIfExists(trashFile);
            AnimLibrary.reload();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 回收站超过上限时删最旧的。 */
    public static void pruneTrash() {
        List<Path> files = trashFiles();
        for (int i = MAX_TRASH; i < files.size(); i++) {
            deleteQuietly(files.get(i));
        }
    }

    private static AnimClip read(String name) {
        Path file = AnimLibrary.fileOf(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return AnimClip.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Throwable ignored) {
        }
    }

    private static String stripJson(String fileName) {
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }
}
