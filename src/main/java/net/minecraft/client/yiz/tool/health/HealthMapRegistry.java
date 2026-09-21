package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.tool.key.FieldHandle;
import net.minecraft.client.yiz.tool.key.UnsafeAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** agent 未就绪时的空表兜底（拿不到类表 = 与旧版一样扫不出东西）。 */
    private static final Map<Class<?>, List<FieldHandle>> NO_MAPS = java.util.Collections.emptyMap();

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

    /**
     * 藏血 Map 判据的枚举结果：全类路径枚举与「静态 Map + K=实体类 + V=数值」的泛型判据
     * 已统一挪到 {@link HealthDiscovery}（三处发现器共享一次枚举、跑在后台线程、只缓存字段句柄）。
     *
     * <p>这里只读最新快照，<b>不再自己枚举类表</b>——原先每次攻击都可能触发
     * {@code getAllLoadedClasses()} + 逐类逐字段 {@code getGenericType()}，是首击 1~3 秒的主因。
     * 发现范围不变：仍是全类路径、仍按类数量变化重扫（节流在 {@code HealthDiscovery} 里）。</p>
     */
    private static Map<Class<?>, List<FieldHandle>> healthMaps() {
        HealthDiscovery.Snapshot snap = HealthDiscovery.current();
        return snap == null ? NO_MAPS : snap.healthMaps();
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
        int bestDepth = -1;
        for (Map.Entry<Class<?>, List<FieldHandle>> e : healthMaps().entrySet()) {
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
