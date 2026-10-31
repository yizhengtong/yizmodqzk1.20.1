package net.minecraft.client.yiz.tool.defense;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个实体类的防御画像（字节码只读扫描产物）。
 *
 * <p>用于 P1：记录被覆写的生命/伤害方法、疑似伤害上限常量、数值字段候选；
 * P2 的字段破除/常量改写以此为输入。</p>
 */
public final class DefenseProfile {

    /** 疑似单次/窗口上限常量（来自 hurt/actuallyHurt/setHealth 内的 Math.min/max 附近）。 */
    public static final class CapCandidate {
        public String owner;
        public String method;
        public double value;
        public String type;   // int / long / float / double

        public CapCandidate() {}

        public CapCandidate(String owner, String method, double value, String type) {
            this.owner = owner;
            this.method = method;
            this.value = value;
            this.type = type;
        }
    }

    /** hurt/actuallyHurt/setHealth 中读写的数值字段候选。 */
    public static final class FieldCandidate {
        public String owner;
        public String name;
        public String desc;   // I / J / F / D / Z
        /** 结构语义：null=按名字的 cap/cooldown 候选；bool=布尔门；time=绝对/游戏时间门；timer=每 tick 递减计时器。 */
        public String kind;

        public FieldCandidate() {}

        public FieldCandidate(String owner, String name, String desc) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
        }

        public FieldCandidate(String owner, String name, String desc, String kind) {
            this(owner, name, desc);
            this.kind = kind;
        }
    }

    public String className;
    public long classHash;
    public long scannedAt;
    public List<String> overrides = new ArrayList<>();
    public List<CapCandidate> caps = new ArrayList<>();
    public List<FieldCandidate> fields = new ArrayList<>();
    /** 结构语义门（bool/time/timer），名字不可靠时也能量到。 */
    public List<FieldCandidate> gates = new ArrayList<>();

    public DefenseProfile() {}

    public boolean isEmpty() {
        return caps.isEmpty() && fields.isEmpty();
    }

    // ==================== JSON 缓存 ====================

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, DefenseProfile> CACHE = new LinkedHashMap<>();

    public static DefenseProfile cached(String className) {
        return CACHE.get(className);
    }

    public static void put(DefenseProfile p) {
        if (p != null && p.className != null) CACHE.put(p.className, p);
    }

    public static Map<String, DefenseProfile> all() {
        return CACHE;
    }

    /** 从磁盘加载缓存（启动时调用一次）。 */
    public static void load(Path file) {
        try {
            if (!Files.exists(file)) return;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, DefenseProfile> m = GSON.fromJson(r,
                    new TypeToken<Map<String, DefenseProfile>>() {}.getType());
                if (m != null) {
                    CACHE.clear();
                    CACHE.putAll(m);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 写回磁盘（全量，量很小）。 */
    public static void save(Path file) {
        try {
            Path dir = file.getParent();
            if (dir != null) Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(CACHE, w);
            }
        } catch (IOException ignored) {}
    }
}
