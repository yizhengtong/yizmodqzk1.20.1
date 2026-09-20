package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.core.asm.AgentBridge;
import net.minecraft.client.yiz.tool.key.FieldHandle;
import net.minecraft.client.yiz.tool.key.UnsafeAccess;
import net.minecraft.client.yiz.tizMod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用「藏血 Map」检测 + 篡改（涨跌多空攻击线的藏血实体分支）。
 *
 * <p>部分实体把真实血量藏在<b>静态 {@code Map} 字段</b>里（而非自身字段 / DataParameter），
 * 并对 Map 的写方法（put/remove/replace…）做调用栈鉴权，外部直改字段会被 Map 值覆盖。
 * 本类按<b>纯类型特征</b>定位这些 Map，并用 {@code unreflectSpecial} 锁基类 {@code put}
 * 绕过鉴权直写——<b>全程不引用任何目标模组的类名/字段名</b>，只认：</p>
 * <ul>
 *   <li>字段是 {@code static}，类型 {@code Map} 或其子类；</li>
 *   <li>泛型 {@code V ∈ Number}（血量数值）、{@code K} 是 {@link Entity} 子类（实体）。</li>
 * </ul>
 * <p>字段名/类名可混淆，但<b>类型 + 泛型签名改不了</b>，故更新/改名后仍命中。</p>
 */
public final class HealthMapRegistry {

    private static final Logger LOGGER = tizMod.LOGGER;

    /** key 类型（实体类）→ 该类型的藏血 Map 字段句柄列表（同 key 类可能有多个 Map，如「当前血量 + 拉回依据」）。 */
    private static volatile Map<Class<?>, List<FieldHandle>> HEALTH_MAPS = new ConcurrentHashMap<>();
    /** 是否已做过一次全局扫描（结果落盘，跨会话直接反解句柄）。 */
    private static volatile boolean scanned = false;
    /** 已做过「按实体类就近发现」的类名（每类一次）。 */
    private static final java.util.Set<String> TARGETED = ConcurrentHashMap.newKeySet();
    /** 落盘缓存分域。 */
    private static final String SECTION = "health_maps";

    /** 全权限 lookup（{@code IMPL_LOOKUP} 非 public，用 Unsafe 直读拿），供 unreflectSpecial 锁基类 put。 */
    private static final MethodHandles.Lookup TRUSTED_LOOKUP = trustedLookup();

    private HealthMapRegistry() {}

    private static MethodHandles.Lookup trustedLookup() {
        try {
            Unsafe u = UnsafeAccess.get();
            if (u == null) return null;
            Field f = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            return (MethodHandles.Lookup) u.getObject(u.staticFieldBase(f), u.staticFieldOffset(f));
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 检测（枚举 + 泛型判据） ====================

    /** 懒加载 + 至多一次全局扫描：优先用落盘发现结果，缺失才枚举类路径。
     *
     *  <p>旧实现按「已加载类数量变化」触发全类路径重扫（2 秒节流）→ 战斗中新类不断加载，
     *  等于每 2 秒把整个类路径反射一遍，首次攻击卡 1~3 秒。现在全局扫描只做一次且结果落盘。</p> */
    public static void ensureScanned() {
        if (scanned) return;
        synchronized (HealthMapRegistry.class) {
            if (scanned) return;
            if (HealthDiscoveryCache.isScanned(SECTION)) {
                Map<Class<?>, List<FieldHandle>> restored = restoreFromCache();
                if (!restored.isEmpty()) {
                    HEALTH_MAPS = restored;
                    scanned = true;
                    LOGGER.info("[HealthDiscovery] health_maps 缓存命中 {} 类，跳过全类路径扫描", restored.size());
                    return;
                }
                // 缓存一条都用不了（类被模组更新移除等）→ 退回一次全局扫描
            }
            int classes = scan();
            scanned = classes > 0;   // 没有 agent / 类表不可用时不置位，留待下次重试
        }
    }

    /** 从落盘缓存反解字段句柄（不枚举类路径）。 */
    private static Map<Class<?>, List<FieldHandle>> restoreFromCache() {
        Map<Class<?>, List<FieldHandle>> out = new ConcurrentHashMap<>();
        for (String[] pair : HealthDiscoveryCache.get(SECTION)) {
            Field f = HealthDiscoveryCache.resolve(pair[0], pair[1]);
            if (f == null) continue;
            Class<?> keyClass = keyClassOf(f);
            if (keyClass == null || !Entity.class.isAssignableFrom(keyClass)) continue;
            FieldHandle h = FieldHandle.of(f);
            if (h == null) continue;
            out.computeIfAbsent(keyClass, k -> new ArrayList<>()).add(h);
        }
        return out;
    }

    /** 按具体实体类就近发现（新加载的实体类不必等下一次全局扫描；只扫该类继承链，成本极低）。 */
    public static void ensureScannedFor(Class<?> entityClass) {
        if (entityClass == null) return;
        ensureScanned();
        if (!TARGETED.add(entityClass.getName())) return;
        try {
            for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (f.isSynthetic() || !Modifier.isStatic(f.getModifiers())) continue;
                        if (!Map.class.isAssignableFrom(f.getType())) continue;
                        Class<?> keyClass = keyClassOf(f);
                        Class<?> valClass = valClassOf(f);
                        if (keyClass == null || valClass == null) continue;
                        if (!Entity.class.isAssignableFrom(keyClass)) continue;
                        if (!Number.class.isAssignableFrom(valClass)) continue;
                        FieldHandle h = FieldHandle.of(f);
                        if (h == null) continue;
                        Map<Class<?>, List<FieldHandle>> cur = HEALTH_MAPS;
                        List<FieldHandle> list = cur.computeIfAbsent(keyClass, k -> new ArrayList<>());
                        if (!list.contains(h)) list.add(h);
                        HealthDiscoveryCache.put(SECTION, c.getName(), f.getName());
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int scan() {
        Class<?>[] all = allLoadedClasses();
        if (all == null || all.length == 0) return -1;
        Map<Class<?>, List<FieldHandle>> fresh = new ConcurrentHashMap<>();
        int hits = 0;
        for (Class<?> clazz : all) {
            try {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.isSynthetic() || !Modifier.isStatic(f.getModifiers())) continue;
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    Class<?> keyClass = keyClassOf(f);
                    Class<?> valClass = valClassOf(f);
                    if (keyClass == null || valClass == null) continue;
                    if (!Entity.class.isAssignableFrom(keyClass)) continue;   // K 是实体
                    if (!Number.class.isAssignableFrom(valClass)) continue;   // V 是数值
                    FieldHandle h = FieldHandle.of(f);
                    if (h == null) continue;
                    fresh.computeIfAbsent(keyClass, k -> new ArrayList<>()).add(h);
                    hits++;
                    HealthDiscoveryCache.put(SECTION, clazz.getName(), f.getName());
                    LOGGER.info("[HealthMap] 识别藏血 Map: {} -> {}", keyClass.getName(), h.describe());
                }
            } catch (Throwable ignored) {}
        }
        HEALTH_MAPS = fresh;   // 原子交换，避免清空/重填期间读竞态
        LOGGER.info("[HealthMap] 藏血 Map 扫描完成，命中 {} 个", hits);
        HealthDiscoveryCache.markScanned(SECTION);   // 全局扫描只做这一次，结果落盘
        return all.length;
    }

    private static Class<?>[] allLoadedClasses() {
        try {
            Instrumentation inst = AgentBridge.getInstrumentation();
            if (inst != null) return inst.getAllLoadedClasses();
        } catch (Throwable ignored) {}
        return null;
    }

    /** 读字段泛型 K（实体 key 类型）；非具体类（TypeVariable/Wildcard）返回 null。 */
    private static Class<?> keyClassOf(Field f) {
        Type gt = f.getGenericType();
        if (!(gt instanceof ParameterizedType pt)) return null;
        Type[] args = pt.getActualTypeArguments();
        if (args == null || args.length != 2) return null;
        return toClass(args[0]);
    }

    /** 读字段泛型 V（血量 value 类型）；非具体类返回 null。 */
    private static Class<?> valClassOf(Field f) {
        Type gt = f.getGenericType();
        if (!(gt instanceof ParameterizedType pt)) return null;
        Type[] args = pt.getActualTypeArguments();
        if (args == null || args.length != 2) return null;
        return toClass(args[1]);
    }

    private static Class<?> toClass(Type t) {
        return t instanceof Class<?> c ? c : null;
    }

    // ==================== 判定 + 篡改 ====================

    /**
     * 目标实体命中的藏血 Map 字段句柄。只取「继承深度最大」的 key 类型——具体实体类的 Map
     * 是「当前血量 + 拉回依据」，而 Entity 基类的 Map 是「最大血量/通用表」，
     * 后者不参与拉回比较、也不该被当作当前血量改，故忽略。
     */
    public static List<FieldHandle> resolveHealthMaps(LivingEntity entity) {
        List<FieldHandle> result = new ArrayList<>();
        if (entity == null) return result;
        ensureScanned();
        ensureScannedFor(entity.getClass());   // 本轮新出现的实体类就近发现（只扫继承链，不枚举类路径）
        int bestDepth = -1;
        for (Map.Entry<Class<?>, List<FieldHandle>> e : HEALTH_MAPS.entrySet()) {
            if (!e.getKey().isInstance(entity)) continue;
            int depth = entityDepth(e.getKey());
            if (depth > bestDepth) {
                bestDepth = depth;
                result.clear();
                result.addAll(e.getValue());
            } else if (depth == bestDepth) {
                result.addAll(e.getValue());
            }
        }
        return result;
    }

    /**
     * 读藏血 Map 里的真实血量：取命中 Map 里该实体条目的最小值（当前血量 ≤ 上限）。非藏血实体返回 null。
     *
     * <p>用 {@code double} 而非 {@code float}：值类型可能是 Long，量级能到 1e12，
     * 而 float 只有 24 位尾数，读进来就会丢精度，之后写回的值与原值对不上。</p>
     */
    public static Double readHealth(LivingEntity entity) {
        Double min = null;
        for (FieldHandle h : resolveHealthMaps(entity)) {
            Object map = h.tryGetObject();
            if (!(map instanceof Map<?, ?> m)) continue;
            try {
                Object v = m.get(entity);   // 只读，不触发写方法鉴权
                if (v instanceof Number n) {
                    double d = n.doubleValue();
                    if (min == null || d < min) min = d;
                }
            } catch (Throwable ignored) {}
        }
        return min;
    }

    /**
     * 直写藏血 Map 的血量（unreflectSpecial 锁基类 put，绕过重写鉴权）。
     * 同步改写命中的<b>所有</b>「当前血量」Map（含「拉回依据」Map），使拉回判定
     * {@code healthValues == lastGoodHealthValues} 恒成立，篡改不被察觉。非藏血实体返回 false。
     */
    public static boolean tamperHealth(LivingEntity entity, double newHealth) {
        List<FieldHandle> maps = resolveHealthMaps(entity);
        if (maps.isEmpty()) return false;
        boolean any = false;
        for (FieldHandle h : maps) {
            Object map = h.tryGetObject();
            if (!(map instanceof Map<?, ?> m)) continue;
            if (putUnchecked(m, entity, boxLikeExisting(m, entity, newHealth))) any = true;
        }
        return any;
    }

    /**
     * 按 Map 中原值的<b>装箱类型</b>写回，不要一律写 Float。
     *
     * <p>血量 Map 的值类型只保证是 {@link Number}，实际可能是 Long/Integer/Double。
     * 读取端用 {@code floatValue()} 兼容了所有类型，写入端若无条件写 Float，
     * 持有方下次按自己的类型取值就会 {@code ClassCastException} —— 崩的是对方的 tick 循环，
     * 崩溃栈里看不到本模组任何一帧，极难定位。</p>
     */
    private static Object boxLikeExisting(Map<?, ?> m, Object key, double newHealth) {
        Object old;
        try {
            old = m.get(key);
        } catch (Throwable ignored) {
            return (float) newHealth;
        }
        if (old instanceof Long) return Math.round(newHealth);
        if (old instanceof Integer) return (int) newHealth;
        if (old instanceof Double) return newHealth;
        if (old instanceof Short) return (short) newHealth;
        if (old instanceof Byte) return (byte) newHealth;
        return (float) newHealth;
    }

    /** key 类型相对 {@link Entity} 的继承深度（Entity 本身为 0；具体实体类更深）。 */
    private static int entityDepth(Class<?> c) {
        int d = 0;
        while (c != null && c != Entity.class) {
            c = c.getSuperclass();
            d++;
        }
        return d;
    }

    // ==================== unreflectSpecial 绕过重写 put ====================

    /**
     * 沿实例类的父链向上找第一个 {@code java.util.*} 下的具体 Map 实现（WeakHashMap/HashMap/…），
     * 用 {@code IMPL_LOOKUP.unreflectSpecial} 锁定其 {@code put} 基类实现，直写绕过子类重写的鉴权。
     * 失败返回 false（不抛），调用方降级走其它改血路径。
     */
    private static boolean putUnchecked(Map<?, ?> map, Object key, Object value) {
        if (TRUSTED_LOOKUP == null) return false;
        Class<?> impl = jdkMapImpl(map.getClass());
        if (impl == null) return false;
        try {
            Method put = impl.getMethod("put", Object.class, Object.class);
            MethodHandle mh = TRUSTED_LOOKUP.unreflectSpecial(put, impl);
            mh.invoke(map, key, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?> jdkMapImpl(Class<?> c) {
        while (c != null) {
            if (c.getName().startsWith("java.util.")
                    && Map.class.isAssignableFrom(c)
                    && !c.isInterface()) {
                return c;
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
