package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.core.asm.AgentBridge;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第三方「权威血量写入口」调用器。
 *
 * <p><b>为什么需要：</b>有些模组自带反作弊血量体系（生产实测 omnimobs：{@code EntityUtil} 把真血存在
 * 自己的结构里，并用 {@code setAllHealthFields/setAllSyncedHealthData/runSetHealthMethods} 每 tick
 * 把所有血量字段回写一遍，另有自己的 {@code DATA_MODIFY_GET_HEALTH_DELTA} 通道）。外部直写血量的
 * 普通通道会被它立刻覆盖——实测「写 0 → 回读仍是 200」——因此表现为「这类实体改不动/打不死」。</p>
 *
 * <p><b>做法：</b>不跟它抢写，而是**调它自己的写入口**，让它按自己的流程更新权威值。发现方式：
 * 按目标实体所在模组的包前缀，在已加载类里找
 * {@code public static void (LivingEntity 子类, float/double)} 且名字含 health 的方法
 * （{@code forceSetHealth} 之类评分最高；{@code setAllSyncedHealthData/setAllHealthFields} 这类
 * 内部步骤会被降权）。结果按实体类缓存，调用后由调用方回读确认，失败不影响原链路。</p>
 */
public final class ForeignHealthAuthority {

    private ForeignHealthAuthority() {}

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;

    /** 实体类名 → 写入口；值为 {@link #NONE} 表示确认没有。 */
    private static final Map<String, Method> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> LOGGED = new ConcurrentHashMap<>();

    /** 哨兵：该类没有可用的第三方写入口。 */
    private static final Method NONE;
    static {
        Method m = null;
        try {
            m = ForeignHealthAuthority.class.getDeclaredMethod("noop");
        } catch (Throwable ignored) {}
        NONE = m;
    }
    @SuppressWarnings("unused")
    private static void noop() {}

    /** 用第三方自己的写入口把血量设为 target；返回是否已调用（落地与否由调用方回读确认）。 */
    public static boolean write(LivingEntity entity, double target) {
        if (entity == null) return false;
        Method m = resolve(entity.getClass());
        if (m == null || m == NONE) return false;
        try {
            Class<?> pt = m.getParameterTypes()[1];
            Object v = pt == float.class ? (Object) (float) target : (Object) target;
            m.invoke(null, entity, v);
            if (LOGGED.putIfAbsent(entity.getClass().getName(), Boolean.TRUE) == null) {
                LOGGER.info("[ForeignAuthority] {} 通过第三方写入口 {}.{}({}, {}) 改血 → {}",
                    entity.getClass().getName(), m.getDeclaringClass().getName(), m.getName(),
                    m.getParameterTypes()[0].getSimpleName(), pt.getSimpleName(), target);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method resolve(Class<?> entityClass) {
        if (entityClass == null) return null;
        Method cached = CACHE.get(entityClass.getName());
        if (cached != null) return cached;
        Method found = discover(entityClass);
        CACHE.put(entityClass.getName(), found == null ? NONE : found);
        return found == null ? NONE : found;
    }

    /** 在目标实体所在模组的包前缀内找写入口（只扫一次、按类缓存）。 */
    private static Method discover(Class<?> entityClass) {
        try {
            String pkg = packagePrefix(entityClass.getName());
            if (pkg.isEmpty()) return null;
            var inst = AgentBridge.getInstrumentation();
            if (inst == null) return null;
            Method best = null;
            int bestScore = 0;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName();
                if (!n.startsWith(pkg) || HealthDiscoveryCache.isOwnClass(n)) continue;
                for (Method m : c.getDeclaredMethods()) {
                    int score = score(m, entityClass);
                    if (score > bestScore) {
                        bestScore = score;
                        best = m;
                    }
                }
            }
            if (best != null) {
                try { best.setAccessible(true); } catch (Throwable ignored) {}
                LOGGER.info("[ForeignAuthority] {} 发现第三方写入口候选 {}.{}({}, {}) 评分={}",
                    entityClass.getName(), best.getDeclaringClass().getName(), best.getName(),
                    best.getParameterTypes()[0].getSimpleName(), best.getParameterTypes()[1].getSimpleName(),
                    bestScore);
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 包前缀：取类名前 2~3 段（{@code flashfur.omnimobs.entities.x.Y} → {@code flashfur.omnimobs}）。 */
    private static String packagePrefix(String className) {
        int idx = className.lastIndexOf('.');
        if (idx <= 0) return "";
        String pkg = className.substring(0, idx);
        int cut = -1;
        for (int i = 0, seen = 0; i < pkg.length(); i++) {
            if (pkg.charAt(i) == '.' && ++seen == 2) {
                cut = i;
                break;
            }
        }
        return cut > 0 ? pkg.substring(0, cut) : pkg;
    }

    /** 写入口评分：静态 + public + (实体, float/double) + 名字像"直接设血"。 */
    private static int score(Method m, Class<?> entityClass) {
        try {
            if (!Modifier.isStatic(m.getModifiers()) || !Modifier.isPublic(m.getModifiers())) return 0;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2) return 0;
            if (!p[0].isAssignableFrom(entityClass)) return 0;
            if (p[1] != float.class && p[1] != double.class) return 0;
            String name = m.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("health")) return 0;
            int s = 1;
            if (name.contains("force")) s += 4;
            if (name.startsWith("set")) s += 2;
            if (name.contains("true") || name.contains("real") || name.contains("exact")) s += 3;
            // 内部步骤（回写全部字段/同步数据/遍历方法）不是入口，降权
            if (name.contains("synced") || name.contains("fields") || name.contains("methods")) s -= 3;
            return Math.max(s, 0);
        } catch (Throwable t) {
            return 0;
        }
    }
}
