package net.minecraft.client.yiz.lightning.fx;

import net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier;
import net.minecraft.world.phys.Vec3;

/**
 * 线段电弧特效实例（纯数据）。
 *
 * <p>里程碑1：空气定向 A→B 单段。里程碑2 将扩展 SURFACE 类型（沿实体包围盒表面游走），
 * 届时 from/to 改由 {@code PositionSupplier} 动态提供。</p>
 *
 * <p>端点支持<b>动态跟随</b>：{@link #fromSup}/{@link #toSup} 非 null 时渲染按
 * {@code partialTick} 实时取插值位置（跟随移动目标不滞后），否则退回固定 {@link #from}/{@link #to} 快照。</p>
 */
public class ArcEffect {

    public Vec3 from;
    public Vec3 to;

    /** 可选动态端点（跟随实体/球）。非 null 时渲染用 partialTick 实时取，忽略固定 from/to。 */
    public final PositionSupplier fromSup;
    public final PositionSupplier toSup;

    /** 随机种子 — 决定抖动形态，每条电弧独立。 */
    public final int seed;

    /** 电弧带宽（格），renderer 取 width*0.5 作半宽。 */
    public final float width;

    public final float maxLife;
    public float life;

    /** 颜色（线性 0..1）。默认蓝白等离子由 LightningFX 注入。 */
    public final float r, g, b;

    /** 固定端点构造（from/to 快照，不跟随）。 */
    public ArcEffect(Vec3 from, Vec3 to, float life, float width, int seed, float r, float g, float b) {
        this(from, to, null, null, life, width, seed, r, g, b);
    }

    /** 动态端点构造（fromSup/toSup 实时跟随；from/to 用 partialTick=0 快照作初始值）。 */
    public ArcEffect(PositionSupplier fromSup, PositionSupplier toSup,
                     float life, float width, int seed, float r, float g, float b) {
        this(fromSup.get(0f), toSup.get(0f), fromSup, toSup, life, width, seed, r, g, b);
    }

    private ArcEffect(Vec3 from, Vec3 to, PositionSupplier fromSup, PositionSupplier toSup,
                      float life, float width, int seed, float r, float g, float b) {
        this.from = from;
        this.to = to;
        this.fromSup = fromSup;
        this.toSup = toSup;
        this.maxLife = life;
        this.life = life;
        this.width = width;
        this.seed = seed;
        this.r = r;
        this.g = g;
        this.b = b;
    }
}
