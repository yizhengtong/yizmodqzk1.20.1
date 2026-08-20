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
            Field hf = findHealthField(store, entity);
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
    public static boolean writeHealth(LivingEntity entity, double target) {
        if (entity == null || entity.level().isClientSide()) return false;
        boolean any = false;
        for (Object store : candidateStores(entity)) {
            if (!holdsEntityRef(store, entity)) continue;
            Field hf = findHealthField(store, entity);
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
        try {
            Class<?>[] all = allLoadedClasses();
            if (all != null) {
                for (Class<?> clazz : all) {
                    for (Field f : clazz.getDeclaredFields()) {
                        int m = f.getModifiers();
                        if (!Modifier.isStatic(m) || f.isSynthetic()) continue;
                        Class<?> t = f.getType();
                        if (t.isPrimitive() || t == String.class || t.isEnum()) continue;
                        if (Map.class.isAssignableFrom(t) || Iterable.class.isAssignableFrom(t)) continue;
                        if (t.getName().startsWith("java.") && !t.getName().startsWith("java.util.UUID")) continue;
                        try {
                            f.setAccessible(true);
                            Object v = f.get(null);
                            if (v != null) out.add(v);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
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

    /** 找对象里 ≈ 当前血 / 最大血的数值字段（血量参考）。 */
    private static Field findHealthField(Object store, LivingEntity entity) {
        try {
            double gh = entity.getHealth();
            double gmh = entity.getMaxHealth();
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
                    double dGh = Math.abs(v - gh);
                    double dGmh = Math.abs(v - gmh);
                    double diff = Math.min(dGh, dGmh);
                    double tol = Math.max(REF_TOL, Math.max(Math.abs(gh), Math.abs(gmh)) * 0.001);
                    if (diff <= tol && diff < bestDiff) {
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
