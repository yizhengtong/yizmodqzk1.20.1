package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.core.asm.AgentBridge;
import net.minecraft.client.yiz.tizMod;
import net.minecraft.client.yiz.tool.key.FieldHandle;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 三处藏血发现器共用的<b>一次全类路径枚举</b>（性能层，发现范围与判据完全不变）。
 *
 * <p><b>为什么要合并：</b>{@link HealthMapRegistry}、{@link ExternalHealthStore}、{@link ExternalRefStore}
 * 原先各自做「{@code getAllLoadedClasses()} + 逐类逐字段反射」，等于把同一次全类路径遍历做三遍，
 * 而且都跑在<b>攻击线程</b>上（生产实测首击「藏血Map/外部」段 1200~1400ms）。这里改为：</p>
 * <ul>
 *   <li><b>一次枚举</b>：一趟类表 + 一趟字段表，按三套判据同时分派；</li>
 *   <li><b>后台线程</b>：枚举在守护线程上做，完成后原子换 {@link Snapshot}；主线程只读最新快照；</li>
 *   <li><b>只缓存判据结论</b>：快照里放的是<b>字段句柄</b>（{@link FieldHandle} / {@link Field}）与
 *       「哪些字段符合判据」的结论——字段集合不会变，所以可以缓存；<b>字段值一律每次调用现读</b>，
 *       绝不缓存解析出来的 Map/对象实例（缓存实例会在第三方换壳后拿到过期引用 → 读不到真血）。</li>
 * </ul>
 *
 * <p><b>范围不变</b>：每次重扫仍是全类路径、全字段，按「类数量变化」触发；节流只是把重扫<b>发起频率</b>
 * 放宽到 {@link #RESCAN_INTERVAL_MS}，不会缩小任何一次扫描的范围。快照为 {@code null} 时
 * （后台预热还没跑完就来了攻击）走同步兜底，宁可吃一次旧的耗时也不让首击丢强度。</p>
 */
public final class HealthDiscovery {

    private static final Logger LOGGER = tizMod.LOGGER;

    /** 重扫节流：距上次「检查类表」超过该间隔才再发起一次全范围重扫。 */
    private static final long RESCAN_INTERVAL_MS = 5000L;

    private HealthDiscovery() {}

    // ==================== 快照（判据结论 + 字段句柄，不含实例） ====================

    /** 一次全范围枚举的解析结果。发布后不再修改内容（只更新 checkedAtMs）。 */
    public static final class Snapshot {

        private final Map<Class<?>, List<FieldHandle>> healthMaps;
        private final List<FieldHandle> externalKeyMaps;
        private final List<Field> staticStores;
        private final int classCount;
        private final long buildMs;
        private final long builtAtMs;
        private volatile long checkedAtMs;

        private Snapshot(Map<Class<?>, List<FieldHandle>> healthMaps,
                         List<FieldHandle> externalKeyMaps,
                         List<Field> staticStores,
                         int classCount, long buildMs, long builtAtMs) {
            this.healthMaps = healthMaps;
            this.externalKeyMaps = externalKeyMaps;
            this.staticStores = staticStores;
            this.classCount = classCount;
            this.buildMs = buildMs;
            this.builtAtMs = builtAtMs;
            this.checkedAtMs = builtAtMs;
        }

        /** K=实体类 → 该类的静态「藏血 Map」字段句柄（{@link HealthMapRegistry} 判据）。 */
        public Map<Class<?>, List<FieldHandle>> healthMaps() { return healthMaps; }

        /** 静态 Map 字段句柄，K 是「实体身份」型（{@link ExternalHealthStore} 判据）。 */
        public List<FieldHandle> externalKeyMaps() { return externalKeyMaps; }

        /** 静态对象字段（{@link ExternalRefStore} 判据）。字段值每次调用现读。 */
        public List<Field> staticStores() { return staticStores; }

        public int classCount() { return classCount; }
        public long buildMs() { return buildMs; }
        public long builtAtMs() { return builtAtMs; }

        public String describe() {
            return "类数=" + classCount + " 藏血Map类=" + healthMaps.size()
                    + " 外部Map候选=" + externalKeyMaps.size() + " 静态对象=" + staticStores.size();
        }
    }

    // ==================== 状态 ====================

    private static final Object LOCK = new Object();
    private static volatile Snapshot SNAPSHOT;
    private static volatile ScheduledExecutorService TICKER;

    /** 已打过「识别藏血 Map」日志的字段描述（去重，避免每轮重扫刷屏）。 */
    private static final Set<String> HIT_LOGGED = ConcurrentHashMap.newKeySet();
    /** 上次「扫描完成」日志里的命中数（-1=还没打过）；只在命中数变化时再打，便于用出现频次验证性能。 */
    private static volatile int lastLoggedHits = -1;

    // ==================== 对外入口 ====================

    /**
     * 主线程入口：取最新快照。快照没有时同步建一次（强度优先），并顺手拉起后台定时重扫。
     *
     * @return 当前快照；{@code null} 表示 agent 还没就绪（拿不到类表），调用方按"无发现"处理。
     */
    public static Snapshot current() {
        Snapshot s = SNAPSHOT;
        if (s != null) {
            ensureTicker();
            return s;
        }
        Snapshot built = buildSync();
        ensureTicker();
        return built;
    }

    /** 启动后预热：把冷启动那次全范围枚举从「首次攻击」挪到后台。 */
    public static void warmupAsync() {
        ensureTicker();
    }

    /** 快照是否已就绪（诊断用）。 */
    public static boolean isReady() {
        return SNAPSHOT != null;
    }

    // ==================== 后台定时重扫 ====================

    /**
     * 后台守护线程每 {@link #RESCAN_INTERVAL_MS} 检查一次类表：类数量变了就全范围重建并原子换快照，
     * 没变就只更新检查时间戳（几乎零成本）。<b>主线程完全不参与枚举</b>，也不被阻塞；
     * 战斗外也持续保鲜，比旧版「只在攻击时才发现新类」更强。
     */
    private static void ensureTicker() {
        if (TICKER != null) return;
        synchronized (LOCK) {
            if (TICKER != null) return;
            ScheduledExecutorService t = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "yiz-health-discovery");
                th.setDaemon(true);
                th.setPriority(Thread.MIN_PRIORITY);   // 后台普查不跟主线程抢 CPU
                return th;
            });
            t.scheduleWithFixedDelay(HealthDiscovery::refreshSafely,
                    0L, RESCAN_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            TICKER = t;
        }
    }

    private static void refreshSafely() {
        try {
            refresh();
        } catch (Throwable t) {
            LOGGER.warn("[HealthDiscovery] 后台重扫异常（保留旧快照）: {}", t.toString());
        }
    }

    /** 后台重扫：类数量没变就只刷新检查时间；变了就全范围重建并原子换快照。 */
    private static void refresh() {
        Snapshot old = SNAPSHOT;
        Class<?>[] all = allLoadedClasses();
        if (all == null || all.length == 0) return;          // agent 未就绪 → 保留旧快照，下次再试
        if (old != null && all.length == old.classCount) {
            old.checkedAtMs = System.currentTimeMillis();
            return;
        }
        publish(build(all, false));
    }

    /** 冷路径：后台预热尚未完成就来调用 → 同步建一次，保证首击不丢发现范围。 */
    private static Snapshot buildSync() {
        synchronized (LOCK) {
            Snapshot s = SNAPSHOT;
            if (s != null) return s;
            Class<?>[] all = allLoadedClasses();
            if (all == null || all.length == 0) return null;
            return publish(build(all, true));
        }
    }

    private static Snapshot publish(Snapshot s) {
        SNAPSHOT = s;
        return s;
    }

    private static Class<?>[] allLoadedClasses() {
        try {
            var inst = AgentBridge.getInstrumentation();
            if (inst != null) return inst.getAllLoadedClasses();
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 一次枚举，三套判据 ====================

    private static Snapshot build(Class<?>[] all, boolean sync) {
        long t0 = System.nanoTime();
        Map<Class<?>, List<FieldHandle>> healthMaps = new LinkedHashMap<>();
        List<FieldHandle> externalKeyMaps = new ArrayList<>();
        List<Field> staticStores = new ArrayList<>();
        int hits = 0;

        for (Class<?> clazz : all) {
            if (clazz == null) continue;
            // 本模组自己的记账 map/对象不是藏血结构（纯正确性过滤，与发现范围无关）
            if (HealthSelfFilter.isOwnClass(clazz.getName())) continue;
            Field[] fields;
            try {
                fields = clazz.getDeclaredFields();
            } catch (Throwable t) {
                continue;   // 字段类型不可解析（NoClassDefFoundError）等 → 跳过该类
            }
            for (Field f : fields) {
                try {
                    if (f.isSynthetic() || !Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();

                    // —— 判据 A/B：静态 Map 字段 ——
                    if (Map.class.isAssignableFrom(ft)) {
                        // getGenericType 很贵：每个静态 Map 字段只解析一次，两个判据共用这份签名
                        Class<?> keyClass = null;
                        Class<?> valClass = null;
                        try {
                            Type gt = f.getGenericType();
                            keyClass = argClass(gt, 0);
                            valClass = argClass(gt, 1);
                        } catch (Throwable ignored) {}

                        // A. 藏血 Map：K 是实体类、V 是数值（HealthMapRegistry 判据）
                        if (keyClass != null && valClass != null
                                && Entity.class.isAssignableFrom(keyClass)
                                && Number.class.isAssignableFrom(valClass)) {
                            FieldHandle h = FieldHandle.of(f);
                            if (h != null) {
                                healthMaps.computeIfAbsent(keyClass, k -> new ArrayList<>()).add(h);
                                hits++;
                                String desc = h.describe();
                                if (HIT_LOGGED.add(desc)) {
                                    LOGGER.info("[HealthMap] 识别藏血 Map: {} -> {}", keyClass.getName(), desc);
                                }
                            }
                        }
                        // B. 外部藏血 Map：K 是实体身份型（UUID/id/串/实体/弱引用）（ExternalHealthStore 判据）
                        if (isEntityKeyClass(keyClass)) {
                            FieldHandle h = FieldHandle.of(f);
                            if (h != null) externalKeyMaps.add(h);
                        }
                        continue;
                    }

                    // —— 判据 C：静态对象字段（ExternalRefStore 判据）——
                    if (isStaticStoreField(ft)) {
                        f.setAccessible(true);   // 只在这里设一次，调用路径上不再碰反射可访问性检查
                        staticStores.add(f);
                    }
                } catch (Throwable ignored) {}
            }
        }

        long buildMs = (System.nanoTime() - t0) / 1_000_000L;
        Snapshot snap = new Snapshot(healthMaps, externalKeyMaps, staticStores,
                all.length, buildMs, System.currentTimeMillis());

        if (hits != lastLoggedHits) {
            lastLoggedHits = hits;
            LOGGER.info("[HealthMap] 藏血 Map 扫描完成，命中 {} 个", hits);
        }
        LOGGER.info("[HealthDiscovery] 全范围枚举完成({}): {} 用时={}ms",
                sync ? "主线程兜底" : "后台", snap.describe(), buildMs);
        return snap;
    }

    // ==================== 判据（与合并前的三个发现器逐条一致） ====================

    /** 泛型第 {@code index} 个实参若是具体类则返回，否则 null（TypeVariable/通配符/非参数化类型）。 */
    private static Class<?> argClass(Type genericType, int index) {
        if (!(genericType instanceof ParameterizedType pt)) return null;
        Type[] args = pt.getActualTypeArguments();
        if (args == null || args.length != 2 || index >= args.length) return null;
        return args[index] instanceof Class<?> c ? c : null;
    }

    /** K 是否为「实体身份」型（UUID / 实体 id / 字符串 / 实体 / 弱引用）。 */
    private static boolean isEntityKeyClass(Class<?> k) {
        if (k == null) return false;
        return k == UUID.class
                || k == Integer.class || k == int.class
                || k == String.class
                || Entity.class.isAssignableFrom(k)
                || k == java.lang.ref.WeakReference.class;
    }

    /** 静态对象字段判据：非原始类型、非字符串/枚举、非集合、非 java.* （UUID 除外）。 */
    private static boolean isStaticStoreField(Class<?> t) {
        if (t.isPrimitive() || t == String.class || t.isEnum()) return false;
        if (Map.class.isAssignableFrom(t) || Iterable.class.isAssignableFrom(t)) return false;
        String n = t.getName();
        return !n.startsWith("java.") || n.startsWith("java.util.UUID");
    }
}
