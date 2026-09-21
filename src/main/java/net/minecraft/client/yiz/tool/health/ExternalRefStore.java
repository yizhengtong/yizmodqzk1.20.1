package net.minecraft.client.yiz.tool.health;

import net.minecraft.server.level.ServerLevel;
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

    /**
     * 本次调用要匹配的实体身份快照（UUID 串/UUID/id/坐标/最大血）。
     *
     * <p>这些东西在旧实现里是<b>每个候选存储对象都重算一遍</b>的（{@code getStringUUID()} 造串、
     * {@code position()} 造 Vec3、{@code getMaxHealth()} 查属性）——候选对象动辄成千上万，
     * 于是每次攻击就是几万次多余分配。同一次调用内实体不会变，跑一次即可，匹配语义完全不变。</p>
     */
    private static final class Target {
        final LivingEntity entity;
        final String uuidStr;
        final UUID uuid;
        final int id;
        final Vec3 pos;
        final double maxHealth;

        Target(LivingEntity entity) {
            this.entity = entity;
            this.uuidStr = entity.getStringUUID();
            this.uuid = entity.getUUID();
            this.id = entity.getId();
            this.pos = entity.position();
            this.maxHealth = entity.getMaxHealth();
        }
    }

    /** 从外部存档/全局对象读真实血量；非此类实体返回 null。 */
    public static Double readHealth(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        Target target = new Target(entity);
        for (Object store : candidateStores(entity)) {
            if (!holdsEntityRef(store, target)) continue;
            Field hf = findHealthField(store, target, entity.getHealth());
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
        Target ref = new Target(entity);
        for (Object store : candidateStores(entity)) {
            if (!holdsEntityRef(store, ref)) continue;
            Field hf = findHealthField(store, ref, current);
            if (hf == null) continue;
            if (writeField(store, hf, target)) any = true;
        }
        return any;
    }

    // ==================== 1. 候选外部存储对象 ====================

    private static List<Object> candidateStores(LivingEntity entity) {
        List<Object> out = new ArrayList<>();
        // a. 世界 SavedData（getDataStorage 缓存里的全部对象）
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
        // b. 静态对象字段（非 Map/集合/字符串/枚举/原始类型）
        //    字段判据由 HealthDiscovery 全类路径枚举一次并缓存「字段句柄」，这里每次调用
        //    用 Field.get(null) 现读字段值 —— 与旧实现一致（会触发声明类 <clinit>，语义不变），
        //    但不再每次攻击都重新 getAllLoadedClasses + 逐类逐字段过滤。
        HealthDiscovery.Snapshot snap = HealthDiscovery.current();
        if (snap != null) {
            for (Field f : snap.staticStores()) {
                try {
                    Object v = f.get(null);
                    if (v != null) out.add(v);
                } catch (Throwable ignored) {}
            }
        }
        return out;
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
    // ==================== 2. 实体引用匹配 ====================

    /** 判断对象是否持有该实体的引用（UUID/坐标/id/实体/弱引用，多形态）。 */
    private static boolean holdsEntityRef(Object store, Target t) {
        try {
            LivingEntity entity = t.entity;
            String uuidStr = t.uuidStr;
            UUID uuid = t.uuid;
            int id = t.id;
            Vec3 pos = t.pos;
            for (Field f : allFields(store.getClass())) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> type = f.getType();
                try {
                    Object v = f.get(store);
                    if (v == null) continue;
                    if (v == entity) return true;
                    if (type == String.class && (v.equals(uuidStr) || v.equals(uuid.toString()))) return true;
                    if (type == UUID.class && v.equals(uuid)) return true;
                    if (type == int.class || type == Integer.class) {
                        if (((Number) v).intValue() == id) return true;
                    }
                    if (Vec3.class.isAssignableFrom(type)) {
                        Vec3 vec = (Vec3) v;
                        if (vec.distanceToSqr(pos) < REF_TOL * REF_TOL) return true;
                    }
                    if (type == WeakReference.class) {
                        if (((WeakReference<?>) v).get() == entity) return true;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ==================== 3. 血量参考字段 ====================

    /** 找对象里「血量参考」数值字段：∈ [参考血−容差, 最大血+容差]，取最接近参考血者。 */
    private static Field findHealthField(Object store, Target t, double reference) {
        try {
            double gmh = t.maxHealth;
            double tol = Math.max(REF_TOL, Math.abs(gmh) * 0.01);
            Field best = null;
            double bestDiff = Double.MAX_VALUE;
            for (Field f : numericFields(store.getClass())) {
                try {
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

    /**
     * 实例字段清单按类缓存（<b>只缓存字段句柄，值每次现读</b>）。
     *
     * <p>候选外部存储对象数量很大（全类路径的静态对象字段），而 {@code holdsEntityRef} 与
     * {@code findHealthField} 每次调用都要遍历它们各自类的实例字段；旧实现每次都
     * {@code getDeclaredFields()} 走一遍继承链并逐个 {@code setAccessible} —— 这是首击
     * 「藏血Map/外部」段耗时的主要来源。字段集合不会变，故按类缓存；可访问性只在这里设一次。</p>
     */
    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    /** 只含数值类型字段的子集（{@link #findHealthField} 用），同样按类缓存。 */
    private static final Map<Class<?>, List<Field>> NUMERIC_FIELD_CACHE = new ConcurrentHashMap<>();

    private static List<Field> allFields(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {}
                    list.add(f);
                }
            }
            return List.copyOf(list);
        });
    }

    /** 数值类型实例字段子集（double/float/int/long），按类缓存。 */
    private static List<Field> numericFields(Class<?> clazz) {
        return NUMERIC_FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            for (Field f : allFields(c)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t == double.class || t == float.class || t == int.class || t == long.class) list.add(f);
            }
            return List.copyOf(list);
        });
    }

    private static void logOnce(LivingEntity entity, Object store, Field f) {
        String key = entity.getClass().getName();
        if (LOGGED.add(key)) {
            LOGGER.info("[ExtRef] {} 命中外部存档对象 {} → 血量参考字段 {}",
                key, store.getClass().getSimpleName(), f.getName());
        }
    }
}
