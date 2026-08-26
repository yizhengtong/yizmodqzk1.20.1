package net.minecraft.client.yiz.lightning.util;

import net.minecraft.client.renderer.ShaderInstance;

/**
 * iTime uniform 注入工具 — 驱动着色器程序化动画。
 * 抽自 {@code ItemRendererStarMixin} 的 iTime 设置逻辑，闪电特效管线复用。
 */
public final class ShaderTimeUtil {

    private static final long START = System.currentTimeMillis();

    private ShaderTimeUtil() {}

    /** 当前动画时间（秒，循环周期 ~1000s 避免浮点精度损失）。 */
    public static float now() {
        return ((System.currentTimeMillis() - START) % 1_000_000L) / 1000f;
    }

    /** 把 iTime 写入指定着色器；着色器无 iTime uniform 时静默跳过。 */
    public static void setITime(ShaderInstance shader) {
        if (shader == null) return;
        var u = shader.getUniform("iTime");
        if (u != null) u.set(now());
    }
}
