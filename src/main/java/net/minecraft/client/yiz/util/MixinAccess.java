package net.minecraft.client.yiz.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mixin 成员反射访问工具 — 替代生产 SRG 环境下不可靠的 {@code @Shadow}。
 *
 * <p>refmap 只包含 {@code @Inject}/{@code @Modify*} 目标方法映射，<b>不含 {@code @Shadow} 成员映射</b>：
 * 生产环境（SRG 命名）@Shadow 成员按开发名找不到目标 → InvalidMixinException 崩溃。对
 * protected/private 成员改用反射按「类型/签名」定位，<b>不依赖 SRG 名</b>（dev official / 生产 srg 通用）。
 * public 成员请直接用强转（reobf 映射）。</p>
 *
 * <p><b>注意：本类必须放在 mixin 包之外</b>（如 util/tool 包）。mixin 包（mixins.json 的 package）内的类
 * 会被 Mixin 框架视为 mixin 专属，非 mixin 代码引用会抛 IllegalClassLoadError。</p>
 */
public final class MixinAccess {

    private MixinAccess() {}

    private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();

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

    /** 按参数签名 + 返回类型定位方法并调用（dev/prod 通用，不依赖 SRG 名），缓存 Method。 */
    @SuppressWarnings("unchecked")
    public static <T> T invoke(Object instance, Class<?> target, Class<?>[] params, Class<?> ret, Object... args) {
        Method m = METHODS.computeIfAbsent(target.getName() + "#" + Arrays.toString(params), k -> {
            for (Method mm : target.getDeclaredMethods()) {
                if (Arrays.equals(mm.getParameterTypes(), params)
                        && (ret == null || mm.getReturnType() == ret)) {
                    try {
                        mm.setAccessible(true);
                    } catch (Throwable ignored) {}
                    return mm;
                }
            }
            return null;
        });
        if (m == null) return null;
        try {
            return (T) m.invoke(instance, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
