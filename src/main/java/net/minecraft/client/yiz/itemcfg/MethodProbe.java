package net.minecraft.client.yiz.itemcfg;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 结构检测探针：按「参数类型 + 返回类型」签名匹配判定覆写（不依赖方法名）。
 *
 * <p>dev(Mojmap) 与 prod(SRG) 运行时方法名不同，用 {@code getMethod(name, ...)} 会在生产漏检。
 * 参数/返回类型是真实类引用（SRG/Mojmap 下相同），遍历 {@code getMethods()} 按签名匹配 + declaringClass
 * 判定（不在 Item/IForgeItem/Object），dev/prod 行为一致。</p>
 */
public record MethodProbe(String name, Class<?> returnType, Class<?>[] params) {

    public static MethodProbe of(String name, Class<?> returnType, Class<?>... params) {
        return new MethodProbe(name, returnType, params);
    }

    /** 目标类是否覆写了该签名方法（declaringClass 不在 Item/IForgeItem/Object）。 */
    public boolean matches(Class<?> clazz) {
        for (Method m : clazz.getMethods()) {
            if (m.isBridge() || m.isSynthetic()) continue;
            if (m.getReturnType() != returnType) continue;
            if (!Arrays.equals(m.getParameterTypes(), params)) continue;
            Class<?> d = m.getDeclaringClass();
            if (d != net.minecraft.world.item.Item.class
                    && d != net.minecraftforge.common.extensions.IForgeItem.class
                    && d != Object.class) {
                return true;
            }
        }
        return false;
    }
}
