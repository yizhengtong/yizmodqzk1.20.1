package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.core.asm.AgentBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部存档 / 全局对象猎杀（外部藏血的第三形态：SavedData + 静态单例对象）。
 *
 * <p>核心洞察：要影响实体的外部数据，<b>必然持有对实体的引用</b>（哪怕只是弱引用/坐标/UUID）。
 * 本类反向扫描：</p>
 * <ol>
 *   <li>枚举候选「外部存储对象」：世界 SavedData（{@code getDataStorage()} 缓存里的全部）+ 所有已加载类的静态对象字段（非 Map/集合/字符串）；</li>
 *   <li>按<b>实体引用</b>定位：对象字段里存在 UUID 字符串 / UUID / 坐标 Vec3 / 实体 id / 实体本身 / 弱引用 命中该实体的，即该实体的外部存储；</li>
 *   <li>在命中对象里找「血量参考数值字段」：double/float/int 且值 ≈ 当前血或最大血；</li>
 *   <li>写目标血（方向按「值≈当前血=直映」判定，写目标值即可）。</li>
 * </ol>
 *
 * <p>全程类型特征 + 行为验证，不依赖任何类名/字段名/包名；扫描范围刻意放大，
 * 覆盖「UUID/坐标/id/实体/弱引用」等多种定位方式，避免漏掉用其它方式定位实体的模组。</p>
 */
public final class ExternalRefStore {

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;
    private static final java.util.Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static final double REF_TOL = 0.5;   // 坐标/血量近似容差

    private ExternalRefStore() {}

    // ==================== 公共 API ====================

    /** 从外部存档/全局对象读真实血量；非此类实体返回 null。 */
    public static Double readHealth(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        for (Object store : candidateStores(entity)) {
            if (!holdsEntityRef(store, entity)) continue;
            Field hf = findHealthField(store, entity, entity.getHealth());
            if (hf == null) continue;
            double v = readField(hf, store);
            if (Double.isFinite(v)) {
                logOnce(entity, store, hf);
                return v;
            }
        }
        return null;
    }

    /** 写真实血量到外部存档/全局对象；返回是否写入。 */
    public static boolean writeHealth(LivingEntity entity, double current, double target) {
        if (entity == null || entity.level().isClientSide()) return false;
        boolean any = false;
        for (Object store : candidateStores(entity)) {
            if (!holdsEntityRef(store, entity)) continue;
            Field hf = findHealthField(store, entity, current);
            if (hf == null) continue;
            if (writeField(store, hf, target)) any = true;
        }
        return any;
    }

    // ==================== 1. 候选外部存储对象 ====================

    /** 落盘缓存分域（发现结果由 {@link HealthDiscoveryCache} 持久化）。 */
    private static final String SECTION = "external_refs";

    /** 已发现的静态「外部存储对象」缓存（读盘反解 或 唯一一次全扫得到）。 */
    private static volatile List<Object> STATIC_STORES = new ArrayList<>();
    /** 是否已完成一次性初始化（读盘反解 或 全类路径扫描一次）；置位后本轮不再枚举类路径。 */
    private static volatile boolean scanned = false;
    /** 已做过「按实体类就近发现」的类名（每类一次）。 */
    private static final java.util.Set<String> TARGETED = ConcurrentHashMap.newKeySet();
    /**
     * 已发现、但当时读到 {@code null} 的静态候选字段（静态单例常在存档/世界加载后才赋值）。
     *
     * <p>旧实现每次调用都把全部已加载类的静态对象字段读一遍 —— 等于每次读血都全类路径反射一次，
     * 而且照样看不到「晚赋值」的字段。现在只补读这些字段句柄，不枚举任何类路径，
     * 成本随待补字段数线性且只减不增。</p>
     */
    private static final List<Field> PENDING_FIELDS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static List<Object> candidateStores(LivingEntity entity) {
        List<Object> out = new ArrayList<>();
        // a. 世界 SavedData（getDataStorage 缓存里的全部对象）—— 随世界/存档变化，每次现查（不枚举类路径）
        try {
            if (entity.level() instanceof ServerLevel sl) {
                Object storage = getDataStorage(sl);
                if (storage != null) {
                    for (Field f : allFields(storage.getClass())) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (!Map.class.isAssignableFrom(f.getType())) continue;
                        try {
                            f.setAccessible(true);
                            Object m = f.get(storage);
                            if (m instanceof Map<?, ?> map) {
                                for (Object v : map.values()) {
                                    if (v != null) out.add(v);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        // b. 静态对象字段（非 Map/集合/字符串/枚举/原始类型）：发现结果落盘，启动后只反解字段句柄；
        //    新加载的实体类按需「就近发现」，不再每次调用都枚举全类路径
        ensureScanned();
        discoverForClass(entity == null ? null : entity.getClass());
        refreshPending();
        out.addAll(STATIC_STORES);
        return out;
    }

    /** 懒加载 + 至多一次全类路径扫描：优先用落盘发现结果，缺失/全部失效才枚举类路径。 */
    private static void ensureScanned() {
        if (scanned) return;
        synchronized (ExternalRefStore.class) {
            if (scanned) return;
            if (HealthDiscoveryCache.isScanned(SECTION)) {
                List<Object> restored = restoreFromCache();
                if (!restored.isEmpty()) {
                    STATIC_STORES = restored;
                    scanned = true;
                    LOGGER.info("[HealthDiscovery] external_refs 缓存命中 {} 条，跳过全类路径扫描", restored.size());
                    return;
                }
                // 缓存一条都用不了（模组更新把类/字段移除等）→ 视为未扫描，退回一次性全扫
                LOGGER.info("[HealthDiscovery] external_refs 缓存 {} 条均不可用，回退一次性全类路径扫描",
                        HealthDiscoveryCache.get(SECTION).size());
            }
            int classes = scanStores();
            scanned = classes > 0;   // 没有 agent / 类表不可用时不置位，留待下次重试（此时不枚举，成本为零）
        }
    }

    /** 从落盘缓存重建内存缓存：只反解字段句柄 + 读值，不枚举任何类。 */
    private static List<Object> restoreFromCache() {
        List<Object> out = new ArrayList<>();
        for (String[] pair : HealthDiscoveryCache.get(SECTION)) {
            try {
                Field f = HealthDiscoveryCache.resolve(pair[0], pair[1]);
                if (f == null || !isCandidateStoreField(f)) continue;   // 类/字段已消失或类型已变
                Object v = readStaticField(f);
                if (v != null) out.add(v);
                else PENDING_FIELDS.add(f);   // 晚点补读
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 按具体实体类就近发现（新加载的实体类不必等下一次全局扫描；只扫该类继承链，成本极低）。 */
    private static void discoverForClass(Class<?> entityClass) {
        if (entityClass == null || !TARGETED.add(entityClass.getName())) return;
        List<Object> found = new ArrayList<>();
        try {
            for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (!isCandidateStoreField(f)) continue;
                        HealthDiscoveryCache.put(SECTION, c.getName(), f.getName());
                        Object v = readStaticField(f);
                        if (v != null) found.add(v);
                        else PENDING_FIELDS.add(f);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        mergeStores(found);
    }

    /** 补读「已发现但读不到值」的字段（只读字段句柄，不枚举类路径）。 */
    private static void refreshPending() {
        if (PENDING_FIELDS.isEmpty()) return;
        List<Object> found = new ArrayList<>();
        for (Field f : PENDING_FIELDS) {
            try {
                Object v = readStaticField(f);
                if (v != null) {
                    found.add(v);
                    PENDING_FIELDS.remove(f);
                }
            } catch (Throwable ignored) {
                PENDING_FIELDS.remove(f);
            }
        }
        mergeStores(found);
    }

    /** 合并新发现的存储对象到缓存（按对象标识去重：第三方对象的 equals 可能有开销/副作用）。 */
    private static synchronized void mergeStores(List<Object> extra) {
        if (extra.isEmpty()) return;
        List<Object> merged = new ArrayList<>(STATIC_STORES);
        for (Object o : extra) {
            boolean dup = false;
            for (Object e : merged) {
                if (e == o) {
                    dup = true;
                    break;
                }
            }
            if (!dup) merged.add(o);
        }
        STATIC_STORES = merged;
    }

    /**
     * 唯一一次全类路径扫描：枚举已加载类的静态对象字段 → 读值 → 命中即落盘。
     *
     * @return 已加载类数量；{@code <0} 表示类表不可用（没有 agent），调用方不置位 {@code scanned} 以便下次重试
     */
    private static int scanStores() {
        Class<?>[] all = allLoadedClasses();
        if (all == null || all.length == 0) return -1;
        List<Object> fresh = new ArrayList<>();
        int hits = 0;
        LOGGER.info("[ExtRef] external_refs 首次全类路径扫描开始（仅此一次，结果落盘）");
        long t0 = System.currentTimeMillis();
        for (Class<?> clazz : all) {
            try {
                for (Field f : clazz.getDeclaredFields()) {
                    if (!isCandidateStoreField(f)) continue;
                    HealthDiscoveryCache.put(SECTION, f.getDeclaringClass().getName(), f.getName());
                    try {
                        Object v = readStaticField(f);
                        if (v != null) {
                            fresh.add(v);
                            hits++;
                        } else {
                            PENDING_FIELDS.add(f);   // 静态单例还没赋值，晚点补读
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        STATIC_STORES = fresh;   // 原子交换，避免清空/重填期间读竞态
        HealthDiscoveryCache.markScanned(SECTION);   // 全局扫描只做这一次，结果落盘
        LOGGER.info("[ExtRef] external_refs 全类路径扫描完成：命中 {} 个静态外部存储（{} 类，耗时 {} ms）",
                hits, all.length, System.currentTimeMillis() - t0);
        return all.length;
    }

    /** 全类路径扫描与「就近发现」共用的字段判据：静态 + 非合成 + 非原始/String/枚举/Map/集合（java.* 除外）。 */
    private static boolean isCandidateStoreField(Field f) {
        int m = f.getModifiers();
        if (!Modifier.isStatic(m) || f.isSynthetic()) return false;
        Class<?> t = f.getType();
        if (t.isPrimitive() || t == String.class || t.isEnum()) return false;
        if (Map.class.isAssignableFrom(t) || Iterable.class.isAssignableFrom(t)) return false;
        if (t.getName().startsWith("java.") && !t.getName().startsWith("java.util.UUID")) return false;
        return true;
    }

    /**
     * 读静态字段值；{@code null} 表示该静态字段还没被赋值。
     *
     * <p>这里沿用原实现的 {@code f.get(null)}（语义不变）：本类原扫描路径就用它，且静态对象字段
     * 大多在 {@code <clinit>} 里赋值，反射 get 顺带完成类初始化正是本类要的语义。</p>
     */
    private static Object readStaticField(Field f) throws Throwable {
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object getDataStorage(ServerLevel sl) {
        try {
            return sl.getDataStorage();
        } catch (Throwable t) {
            try {
                Method m = ServerLevel.class.getMethod("m_8895_");
                return m.invoke(sl);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static Class<?>[] allLoadedClasses() {
        try {
            var inst = AgentBridge.getInstrumentation();
            if (inst != null) return inst.getAllLoadedClasses();
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 2. 实体引用匹配 ====================

    /** 判断对象是否持有该实体的引用（UUID/坐标/id/实体/弱引用，多形态）。 */
    private static boolean holdsEntityRef(Object store, LivingEntity entity) {
        try {
            String uuidStr = entity.getStringUUID();
            UUID uuid = entity.getUUID();
            int id = entity.getId();
            Vec3 pos = entity.position();
            for (Field f : allFields(store.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                try {
                    f.setAccessible(true);
                    Object v = f.get(store);
                    if (v == null) continue;
                    if (v == entity) return true;
                    if (t == String.class && (v.equals(uuidStr) || v.equals(uuid.toString()))) return true;
                    if (t == UUID.class && v.equals(uuid)) return true;
                    if (t == int.class || t == Integer.class) {
                        if (((Number) v).intValue() == id) return true;
                    }
                    if (Vec3.class.isAssignableFrom(t)) {
                        Vec3 vec = (Vec3) v;
                        if (vec.distanceToSqr(pos) < REF_TOL * REF_TOL) return true;
                    }
                    if (t == WeakReference.class) {
                        if (((WeakReference<?>) v).get() == entity) return true;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ==================== 3. 血量参考字段 ====================

    /** 找对象里「血量参考」数值字段：∈ [参考血−容差, 最大血+容差]，取最接近参考血者。 */
    private static Field findHealthField(Object store, LivingEntity entity, double reference) {
        try {
            double gmh = entity.getMaxHealth();
            double tol = Math.max(REF_TOL, Math.abs(gmh) * 0.01);
            Field best = null;
            double bestDiff = Double.MAX_VALUE;
            for (Field f : allFields(store.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t != double.class && t != float.class && t != int.class && t != long.class) continue;
                try {
                    f.setAccessible(true);
                    double v = readField(f, store);
                    if (!Double.isFinite(v)) continue;
                    // 血量参考字段必然滞后于当前血、不超过最大血（∈ [reference−tol, maxHealth+tol]）
                    if (v < reference - tol || v > gmh + tol) continue;
                    double diff = Math.abs(v - reference);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = f;
                    }
                } catch (Throwable ignored) {}
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ==================== 4. 字段读写 ====================

    private static double readField(Field f, Object obj) {
        try {
            Class<?> t = f.getType();
            if (t == double.class) return f.getDouble(obj);
            if (t == float.class) return f.getFloat(obj);
            if (t == long.class) return f.getLong(obj);
            return f.getInt(obj);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private static boolean writeField(Object obj, Field f, double v) {
        try {
            f.setAccessible(true);
            Class<?> t = f.getType();
            if (t == double.class) f.setDouble(obj, v);
            else if (t == float.class) f.setFloat(obj, (float) v);
            else if (t == long.class) f.setLong(obj, (long) Math.round(v));
            else f.setInt(obj, (int) Math.round(v));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<Field> allFields(Class<?> clazz) {
        List<Field> list = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) list.add(f);
        }
        return list;
    }

    private static void logOnce(LivingEntity entity, Object store, Field f) {
        String key = entity.getClass().getName();
        if (LOGGED.add(key)) {
            LOGGER.info("[ExtRef] {} 命中外部存档对象 {} → 血量参考字段 {}",
                key, store.getClass().getSimpleName(), f.getName());
        }
    }
}
