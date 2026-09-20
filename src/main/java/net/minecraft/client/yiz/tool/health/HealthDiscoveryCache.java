package net.minecraft.client.yiz.tool.health;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部藏血「发现结果」落盘缓存。
 *
 * <p><b>为什么必须落盘：</b>发现外部藏血 Map/单例要枚举<b>全部已加载类</b>
 * （{@code Instrumentation.getAllLoadedClasses()} + 逐类逐字段反射），冷启动一次要 1~3 秒。
 * 之前这套发现只存在内存里、且按「类数量变化」自动重扫（战斗中新类不断加载 → 每 2 秒全类路径
 * 重扫一次），表现就是「每次进存档后第 1 次攻击卡一两秒」+ 战斗中持续卡顿。
 * 现在：发现结果（声明类 + 字段名）落盘，启动后首次使用直接反解句柄，不再枚举类路径；
 * 全局扫描只在缓存缺失/失效时做一次。</p>
 *
 * <p>按 section 分域（各扫描器互不干扰）：{@code health_maps} / {@code external_maps} /
 * {@code external_refs}。</p>
 */
public final class HealthDiscoveryCache {

    private HealthDiscoveryCache() {}

    private static final String FILE_NAME = "yizmodqzk/health_discovery.json";
    private static final int VERSION = 1;

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;

    /** section → 已发现条目（owner 类名 + 字段名）。 */
    private static final Map<String, List<String[]>> ENTRIES = new ConcurrentHashMap<>();
    /** section → 是否已完成过一次全局扫描（落盘，避免每次启动都全类路径扫）。 */
    private static final Set<String> SCANNED = ConcurrentHashMap.newKeySet();

    private static volatile boolean loaded = false;
    private static volatile boolean dirty = false;
    /** 上次写盘时间戳（节流用）。 */
    private static volatile long lastSaveMs = 0L;
    /** 写盘节流窗口：一次扫描内多条发现合并成一次写。 */
    private static final long SAVE_THROTTLE_MS = 1000L;

    public static synchronized void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            if (!Files.isRegularFile(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (root.has("version") && root.get("version").getAsInt() != VERSION) return;
            JsonObject scanned = root.getAsJsonObject("scanned");
            if (scanned != null) {
                for (String k : scanned.keySet()) {
                    try {
                        if (scanned.get(k).getAsBoolean()) SCANNED.add(k);
                    } catch (Throwable ignored) {}
                }
            }
            JsonObject entries = root.getAsJsonObject("entries");
            if (entries != null) {
                for (String section : entries.keySet()) {
                    try {
                        JsonArray arr = entries.getAsJsonArray(section);
                        List<String[]> list = new ArrayList<>();
                        for (JsonElement e : arr) {
                            JsonObject o = e.getAsJsonObject();
                            String owner = o.get("owner").getAsString();
                            String field = o.get("field").getAsString();
                            if (owner != null && !owner.isEmpty() && field != null && !field.isEmpty()) {
                                list.add(new String[]{owner, field});
                            }
                        }
                        if (!list.isEmpty()) ENTRIES.put(section, list);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[HealthDiscovery] 缓存读取失败，将重新扫描: {}", t.toString());
        }
    }

    /** 该域是否已完成过全局扫描（可跳过全类路径扫描）。 */
    public static boolean isScanned(String section) {
        loadIfNeeded();
        return SCANNED.contains(section);
    }

    public static void markScanned(String section) {
        loadIfNeeded();
        if (SCANNED.add(section)) saveNow();
    }

    /** 记录一条发现（owner 类 + 静态字段名）。重复不再写入、不刷盘。 */
    public static void put(String section, String owner, String field) {
        if (owner == null || owner.isEmpty() || field == null || field.isEmpty()) return;
        loadIfNeeded();
        List<String[]> list = ENTRIES.computeIfAbsent(section, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        for (String[] e : list) {
            if (e[0].equals(owner) && e[1].equals(field)) return;
        }
        list.add(new String[]{owner, field});
        save();
    }

    /** 读取该域全部发现条目（可能为空）。 */
    public static List<String[]> get(String section) {
        loadIfNeeded();
        List<String[]> list = ENTRIES.get(section);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 解析 owner#field 为反射字段；解析失败（类/字段已不存在）返回 null。 */
    public static java.lang.reflect.Field resolve(String owner, String field) {
        try {
            Class<?> c = Class.forName(owner, false, HealthDiscoveryCache.class.getClassLoader());
            java.lang.reflect.Field f = c.getDeclaredField(field);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 节流落盘：一次扫描里可能有几百条发现（ExternalRefStore 判据较宽），不能每条都写文件。
     *  条目先进内存，1 秒内合并成一次写；扫描收尾的 {@link #markScanned} 会强制立即写。 */
    public static void save() {
        loadIfNeeded();
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastSaveMs < SAVE_THROTTLE_MS) return;
        saveNow();
    }

    /** 立即写盘（忽略节流）。 */
    public static synchronized void saveNow() {
        dirty = true;
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            Files.createDirectories(p.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", VERSION);
            JsonObject scanned = new JsonObject();
            for (String s : SCANNED) scanned.addProperty(s, true);
            root.add("scanned", scanned);
            JsonObject entries = new JsonObject();
            for (Map.Entry<String, List<String[]>> e : ENTRIES.entrySet()) {
                JsonArray arr = new JsonArray();
                for (String[] pair : e.getValue()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("owner", pair[0]);
                    o.addProperty("field", pair[1]);
                    arr.add(o);
                }
                entries.add(e.getKey(), arr);
            }
            root.add("entries", entries);
            Files.writeString(p, root.toString());
            dirty = false;
            lastSaveMs = System.currentTimeMillis();
        } catch (Throwable t) {
            LOGGER.warn("[HealthDiscovery] 缓存写入失败: {}", t.toString());
        }
    }
}
