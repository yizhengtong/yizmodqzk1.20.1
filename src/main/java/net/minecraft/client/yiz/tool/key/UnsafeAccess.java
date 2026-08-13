package net.minecraft.client.yiz.tool.key;

import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Unsafe 单例（KeyHunter 专用，自包含）。
 *
 * <p>获取路径与外部模组同款（构造器 → theUnsafe 兜底），说明该环境两路均可用。
 * 所有 KeyHunter 内存读写都走这里：不 setAccessible、不产生 {@code java.lang.reflect.*}
 * 调用帧——这是攻破「调用栈鉴权」类内部 key 判定的物理前提。</p>
 */
@SuppressWarnings("removal")
public final class UnsafeAccess {

    private static final Unsafe U;

    static {
        Unsafe u = null;
        try {
            Constructor<Unsafe> ctor = Unsafe.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            u = ctor.newInstance();
        } catch (Throwable t) {
            try {
                Field f = Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                u = (Unsafe) f.get(null);
            } catch (Throwable ignored) {
                // 两路都失败 → 后续所有操作降级为 null/0
            }
        }
        U = u;
    }

    private UnsafeAccess() {}

    public static boolean available() {
        return U != null;
    }

    public static Unsafe get() {
        return U;
    }

    /** Unsafe 不可用时抛出的统一异常。 */
    public static IllegalStateException unavailable() {
        return new IllegalStateException("sun.misc.Unsafe unavailable in this JVM");
    }
}
