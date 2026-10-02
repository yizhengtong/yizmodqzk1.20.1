package net.minecraft.client.yiz.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Mixin 成员反射访问工具 — 替代生产 SRG 环境下不可靠的 {@code @Shadow}。
 *
 * <p>refmap 只包含 {@code @Inject}/{@code @Modify*} 目标方法映射，<b>不含 {@code @Shadow} 成员映射</b>：
 * 生产环境（SRG 命名）@Shadow 成员按开发名找不到目标 → InvalidMixinException 崩溃。对
 * protected/private 成员改用反射按「类型/签名」定位，<b>不依赖 SRG 名</b>（dev official / 生产 srg 通用）。
 * public 成员请直接用强转（reobf 映射）。</p>
 *
 * <p><b>二义性保护（2026-10-03）</b>：按签名定位时，如果 (参数签名 + 返回类型) 命中的方法不止一个，
 * 旧实现会「取第一个」——曾导致「空参 + boolean」在 Minecraft 的 28 个候选里挑错方法、静默失效。
 * 现在改为：<b>候选 &gt; 1 时打一条 warn（含候选名单）并返回 null，绝不猜</b>；需要消歧时用带
 * {@code selector} 的重载（selector 只能依赖不随映射变化的信息：static 与否、参数/返回类型、注解，
 * <b>不要用方法名</b>，生产环境是 SRG 名）。</p>
 *
 * <p><b>注意：本类必须放在 mixin 包之外</b>（如 util/tool 包）。mixin 包（mixins.json 的 package）内的类
 * 会被 Mixin 框架视为 mixin 专属，非 mixin 代码引用会抛 IllegalClassLoadError。</p>
 */
public final class MixinAccess {

    private MixinAccess() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("YizMixinAccess");

    private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
    /** 已告警过的二义键（避免每 tick 刷日志）。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** 按字段类型定位实例字段（第 index 个匹配），缓存 Field。类型精确匹配或兼容。 */
    @SuppressWarnings("unchecked")
    public static <T> T field(Object instance, Class<?> target, Class<?> fieldType, int index) {
        Field f = FIELDS.computeIfAbsent(target.getName() + "#" + fieldType.getName() + "#" + index, k -> {
            int i = 0;
            for (Field ff : target.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(ff.getType()) || ff.getType().isAssignableFrom(fieldType)) {
                    if (i++ == index) {
                        try {
                            ff.setAccessible(true);
                        } catch (Throwable ignored) {}
                        return ff;
                    }
                }
            }
            return null;
        });
        if (f == null) return null;
        try {
            return (T) f.get(instance);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按参数签名 + 返回类型定位方法并调用（dev/prod 通用，不依赖 SRG 名）。
     * 命中多个候选时返回 null 并打 warn（见类注释），不猜测。
     */
    public static <T> T invoke(Object instance, Class<?> target, Class<?>[] params, Class<?> ret, Object... args) {
        return invoke(instance, target, params, ret, null, args);
    }

    /**
     * 带消歧选择器的重载。仅当 (参数签名 + 返回类型) 命中多个方法时才需要 selector；
     * selector 不得依赖方法名（生产环境是 SRG 名），可用 {@code Modifier.isStatic(m.getModifiers())}、
     * 参数/返回类型、注解等。
     *
     * <p>带 selector 的查找不缓存（lambda 无法作为稳定缓存键），调用方若在热路径请改为无 selector 的
     * 唯一签名调用。</p>
     */
    @SuppressWarnings("unchecked")
    public static <T> T invoke(Object instance, Class<?> target, Class<?>[] params, Class<?> ret,
                               Predicate<Method> selector, Object... args) {
        Method m = resolve(target, params, ret, selector);
        if (m == null) return null;
        try {
            return (T) m.invoke(instance, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method resolve(Class<?> target, Class<?>[] params, Class<?> ret, Predicate<Method> selector) {
        if (selector != null) {
            List<Method> hits = collect(target, params, ret, selector);
            if (hits.size() == 1) return hits.get(0);
            warnAmbiguous(target, params, ret, hits);
            return null;
        }
        String key = target.getName() + "#" + Arrays.toString(params) + "->" + (ret == null ? "*" : ret.getName());
        Method cached = METHODS.get(key);
        if (cached != null) return cached;
        List<Method> hits = collect(target, params, ret, null);
        if (hits.size() != 1) {
            warnAmbiguous(target, params, ret, hits);
            return null;   // 不缓存负结果：万一后续调用带了 selector 仍可命中
        }
        Method m = hits.get(0);
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {}
        METHODS.put(key, m);
        return m;
    }

    /** 收集所有匹配 (参数签名 + 返回类型 + 可选 selector) 的声明方法。 */
    private static List<Method> collect(Class<?> target, Class<?>[] params, Class<?> ret, Predicate<Method> selector) {
        List<Method> hits = new ArrayList<>(2);
        for (Method mm : target.getDeclaredMethods()) {
            if (!Arrays.equals(mm.getParameterTypes(), params)) continue;
            if (ret != null && mm.getReturnType() != ret) continue;
            if (selector != null && !selector.test(mm)) continue;
            hits.add(mm);
        }
        return hits;
    }

    private static void warnAmbiguous(Class<?> target, Class<?>[] params, Class<?> ret, List<Method> hits) {
        if (hits.isEmpty()) return;   // 没命中属于正常（可选 API），不吵
        String key = target.getName() + "#" + Arrays.toString(params) + "->" + (ret == null ? "*" : ret.getName());
        if (!WARNED.add(key)) return;
        StringBuilder sb = new StringBuilder();
        for (Method m : hits) {
            sb.append("\n    - ").append(m.getReturnType().getSimpleName()).append(' ').append(m.getName())
              .append('(').append(m.getParameterCount()).append(" args)");
        }
        LOGGER.warn("[MixinAccess] 签名二义，已放弃猜测（返回 null）：{}{}\n  请改用带 selector 的 invoke 重载消歧",
                key, sb);
    }
}
