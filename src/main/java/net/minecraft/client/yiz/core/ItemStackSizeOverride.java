package net.minecraft.client.yiz.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * 物品最大堆叠数覆盖表（运行时拦截 + 双文件持久化）。
 *
 * <p>1.20.1 中最大堆叠数取值链：{@code ItemStack#getMaxStackSize()} →
 * {@code Item#getMaxStackSize(stack)} → 读 {@code Item.Properties#maxStackSize}。
 * 本类提供「物品 ID → 自定义堆叠数」的映射，由 {@code ItemStackMaxSizeMixin} 在
 * {@code ItemStack#getMaxStackSize()} 入口查表，命中则返回自定义值，实现对任意已注册
 * 物品（原版 + 所有 mod）的运行时改堆叠数。</p>
 *
 * <h3>双层隔离：先天默认 vs 后天设置</h3>
 * 为避免「下游 mod 注册的默认配置」与「玩家运行时自定义」互相污染，状态分两层存储：
 * <ul>
 *   <li><b>先天默认</b>（{@code DEFAULTS}）— 下游 mod 通过 {@link #setIfAbsent} 在启动时注册
 *       （如碗/桶/药水/附魔书=16）。存 {@code config/yizmodqzk-stacksize-defaults.json}，
 *       运行时生成，玩家一般不直接编辑。</li>
 *   <li><b>后天设置</b>（{@code OVERRIDES}）— 玩家通过 {@code /yiz stack set} 运行时设置。
 *       存 {@code config/yizmodqzk-stacksize.json}。优先级<b>高于</b>先天默认。</li>
 * </ul>
 * 查询时 {@link #getOverride} 先查后天、未命中再查先天；两层都未命中返回 -1（走原版）。
 *
 * <p>属<b>全局创作偏好</b>（非世界隔离），跨所有存档保留。</p>
 */
public final class ItemStackSizeOverride {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemStackSize");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 后天设置文件（玩家 /yiz stack set 的值，优先级高） */
    private static final String OVERRIDES_FILE = "yizmodqzk-stacksize.json";
    /** 先天默认文件（下游 mod setIfAbsent 注册的默认值） */
    private static final String DEFAULTS_FILE = "yizmodqzk-stacksize-defaults.json";
    /** 堆叠核心强化次数文件（物品 ID → 已强化次数，上限 2） */
    private static final String ENHANCE_FILE = "yizmodqzk-stacksize-enhance.json";

    /** 合法堆叠数边界：1 ~ 99（原版 Container/Slot 与 ItemStack 序列化共同强制）。 */
    public static final int MIN = 1;
    public static final int MAX = 99;

    /** 后天设置：玩家运行时自定义（优先级高） */
    private static final Map<ResourceLocation, Integer> OVERRIDES = new ConcurrentHashMap<>();
    /** 先天默认：下游 mod 启动时注册的默认配置（优先级低） */
    private static final Map<ResourceLocation, Integer> DEFAULTS = new ConcurrentHashMap<>();
    /** 堆叠核心强化次数：物品 ID → 已强化次数（0/1/2，最多 2 次） */
    private static final Map<ResourceLocation, Integer> ENHANCE_COUNT = new ConcurrentHashMap<>();
    /** 单个物品 ID 最多强化次数 */
    public static final int MAX_ENHANCE = 2;

    private static volatile boolean defaultsLoaded = false;
    /** 当前已加载后天/强化次数数据的存档目录路径，切换存档时自动重载 */
    @Nullable
    private static volatile java.nio.file.Path activeWorldPath = null;

    private ItemStackSizeOverride() {}

    // ══════════════════════════════════════════════════════════
    //  持久化
    // ══════════════════════════════════════════════════════════

    private static File getConfigDir() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return new File(mc.gameDirectory, "config");
        } catch (Exception e) {
            return new File("."); // fallback
        }
    }

    /** 先天默认文件：全局（所有存档共享） */
    private static File getDefaultsFile() {
        return new File(getConfigDir(), DEFAULTS_FILE);
    }

    /** 存档内专用子目录（装备叠层同款约定） */
    private static final String DIR_NAME = "yizmodqzk";

    /**
     * 当前存档的专用数据目录（null = 无存档加载）。
     * 单机：{@code saves/<存档名>/yizmodqzk/}，天然按存档隔离、删档即数据消失。
     */
    @Nullable
    private static java.nio.file.Path getWorldDir() {
        try {
            var server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                java.nio.file.Path saveDir = server.getWorldPath(LevelResource.ROOT);
                return saveDir.resolve(DIR_NAME);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 后天设置文件：存放于存档专用目录（{@code saves/<存档>/yizmodqzk/yizmodqzk-stacksize.json}），
     * 删档=数据随之消失；同名新档=真干净。
     */
    private static File getOverridesFile() {
        java.nio.file.Path worldDir = getWorldDir();
        return worldDir != null ? worldDir.resolve(OVERRIDES_FILE).toFile()
                : new File(getConfigDir(), OVERRIDES_FILE);
    }

    /**
     * 强化次数文件：存放于存档专用目录（{@code saves/<存档>/yizmodqzk/yizmodqzk-stacksize-enhance.json}）
     */
    private static File getEnhanceFile() {
        java.nio.file.Path worldDir = getWorldDir();
        return worldDir != null ? worldDir.resolve(ENHANCE_FILE).toFile()
                : new File(getConfigDir(), ENHANCE_FILE);
    }

    /**
     * 加载全局默认 + 确保当前存档的后天/强化次数数据已加载。
     * <p>先天默认全局只加载一次；后天/强化次数按存档隔离，切换存档时自动重载。</p>
     */
    public static synchronized void load() {
        if (!defaultsLoaded) {
            defaultsLoaded = true;
            loadInto(getDefaultsFile(), DEFAULTS, "defaults");
        }
        ensureWorldLoaded();
    }

    /**
     * 确保当前存档的后天 / 强化次数数据已加载。
     * 若存档切换（save 目录变化）则清空旧数据并重载。
     */
    private static void ensureWorldLoaded() {
        java.nio.file.Path worldPath = getWorldDir();
        if (worldPath == null) return; // 无存档，后天/次数为空
        if (worldPath.equals(activeWorldPath)) return; // 已加载

        // 存档首次加载或切换 → 重载
        OVERRIDES.clear();
        ENHANCE_COUNT.clear();
        loadInto(getOverridesFile(), OVERRIDES, "overrides");
        loadInto(getEnhanceFile(), ENHANCE_COUNT, "enhance");
        activeWorldPath = worldPath;
        LOGGER.info("Activated stack-size world data from {} ({} overrides, {} enhanced)",
                worldPath.getFileName(), OVERRIDES.size(), ENHANCE_COUNT.size());
    }

    private static void loadInto(File file, Map<ResourceLocation, Integer> target, String label) {
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, Integer>>() {}.getType();
            Map<String, Integer> raw = GSON.fromJson(reader, type);
            if (raw != null) {
                target.clear();
                raw.forEach((key, value) -> {
                    ResourceLocation id = ResourceLocation.tryParse(key);
                    if (id != null && value != null) {
                        target.put(id, clamp(value));
                    }
                });
                LOGGER.info("Loaded {} stack-size {} from {}", target.size(), label, file.getName());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load stack-size {} from {}", label, file, e);
        }
    }

    /** 保存后天设置到文件。 */
    private static synchronized void saveOverrides() {
        saveTo(getOverridesFile(), OVERRIDES);
    }

    /** 保存先天默认到文件。 */
    private static synchronized void saveDefaults() {
        saveTo(getDefaultsFile(), DEFAULTS);
    }

    private static void saveTo(File file, Map<ResourceLocation, Integer> source) {
        try {
            file.getParentFile().mkdirs();
            Map<String, Integer> raw = new TreeMap<>();
            source.forEach((id, size) -> raw.put(id.toString(), size));
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(raw, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save stack-size overrides to {}", file, e);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  核心 API
    // ══════════════════════════════════════════════════════════

    /** 夹紧到合法区间。 */
    private static int clamp(int size) {
        return Math.max(MIN, Math.min(MAX, size));
    }

    /**
     * 玩家运行时设置物品的最大堆叠数（写<b>后天</b>表，优先级高于先天默认）。
     *
     * @param itemId 目标物品 ID
     * @param size   目标堆叠数，会被夹紧到 [{@value MIN}, {@value MAX}]
     * @return 实际写入的（夹紧后的）堆叠数
     */
    public static int set(ResourceLocation itemId, int size) {
        if (itemId == null) return -1;
        load();
        int clamped = clamp(size);
        OVERRIDES.put(itemId, clamped);
        saveOverrides();
        LOGGER.info("Set max stack size of {} to {}", itemId, clamped);
        return clamped;
    }

    /**
     * 移除某物品的<b>后天</b>设置。
     * <p>该物品若有先天默认（{@link #setIfAbsent} 注册的）则回退到先天默认值；
     * 否则回原版。不会动先天默认表。</p>
     *
     * @return true 表示原本存在后天设置并被移除
     */
    public static boolean reset(ResourceLocation itemId) {
        if (itemId == null) return false;
        load();
        boolean removed = OVERRIDES.remove(itemId) != null;
        if (removed) {
            saveOverrides();
            Integer def = DEFAULTS.get(itemId);
            LOGGER.info("Reset {} ; reverted to {}", itemId,
                    def != null ? "default " + def : "vanilla");
        }
        return removed;
    }

    /** 清空所有<b>后天</b>设置（不动先天默认）。 */
    public static int clear() {
        load();
        int n = OVERRIDES.size();
        OVERRIDES.clear();
        saveOverrides();
        LOGGER.info("Cleared all {} stack-size overrides (defaults untouched, {} remain)",
                n, DEFAULTS.size());
        return n;
    }

    /**
     * 查表（后天优先）：返回该物品的有效堆叠数；未覆盖返回 -1。
     * <p>由 Mixin 层每次 {@code getMaxStackSize()} 调用查询，必须轻量。</p>
     */
    public static int getOverride(ResourceLocation itemId) {
        if (itemId == null) return -1;
        ensureWorldLoaded();
        Integer v = OVERRIDES.get(itemId);
        if (v != null) return v;
        v = DEFAULTS.get(itemId);
        return v == null ? -1 : v;
    }

    /**
     * 是否存在任意层覆盖（先天或后天）。
     */
    public static boolean isOverridden(ResourceLocation itemId) {
        ensureWorldLoaded();
        return itemId != null && (OVERRIDES.containsKey(itemId) || DEFAULTS.containsKey(itemId));
    }

    /**
     * 返回合并视图快照（按 ID 字符串排序，用于 list 指令）。
     */
    public static Map<ResourceLocation, Integer> snapshot() {
        load();
        Map<ResourceLocation, Integer> sorted = new TreeMap<>(
                (a, b) -> a.toString().compareTo(b.toString()));
        sorted.putAll(DEFAULTS);
        sorted.putAll(OVERRIDES); // 后天覆盖先天
        return sorted;
    }

    /** 当前生效的覆盖条目数量（合并去重后）。 */
    public static int size() {
        load();
        java.util.Set<ResourceLocation> ids = new java.util.HashSet<>(DEFAULTS.keySet());
        ids.addAll(OVERRIDES.keySet());
        return ids.size();
    }

    /**
     * 注册<b>先天默认</b>堆叠数 —— 写先天表，不覆盖玩家后天设置。
     *
     * @param itemId 目标物品 ID
     * @param size   默认堆叠数，会被夹紧到 [{@value MIN}, {@value MAX}]
     */
    public static void setIfAbsent(ResourceLocation itemId, int size) {
        if (itemId == null) return;
        load();
        int clamped = clamp(size);
        Integer prev = DEFAULTS.put(itemId, clamped);
        saveDefaults();
        if (prev == null) {
            LOGGER.info("Registered default max stack size of {} to {}", itemId, clamped);
        } else if (prev != clamped) {
            LOGGER.info("Updated default max stack size of {} from {} to {}", itemId, prev, clamped);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  堆叠核心强化次数（物品 ID 粒度，最多 MAX_ENHANCE 次）
    // ══════════════════════════════════════════════════════════

    /**
     * 查询某物品已用堆叠核心强化的次数（0 ~ {@value MAX_ENHANCE}）。
     */
    public static int getEnhanceCount(ResourceLocation itemId) {
        if (itemId == null) return 0;
        load();
        Integer v = ENHANCE_COUNT.get(itemId);
        return v == null ? 0 : v;
    }

    /**
     * 该物品是否还能继续强化（次数未达上限）。
     */
    public static boolean canEnhance(ResourceLocation itemId) {
        return getEnhanceCount(itemId) < MAX_ENHANCE;
    }

    /**
     * 累加某物品的强化次数并持久化。调用方应先 {@link #canEnhance} 判定。
     */
    public static void incrementEnhanceCount(ResourceLocation itemId) {
        if (itemId == null) return;
        load();
        int next = getEnhanceCount(itemId) + 1;
        ENHANCE_COUNT.put(itemId, next);
        saveTo(getEnhanceFile(), ENHANCE_COUNT);
        LOGGER.info("Enhance count of {} → {}", itemId, next);
    }
}
