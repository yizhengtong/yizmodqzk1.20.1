package net.minecraft.client.yiz.lightning.fx;

import net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier;
import net.minecraft.world.phys.Vec3;

/**
 * 火花特效实例（纯数据）— 亮点 + 沿 velocity 的拖尾。
 *
 * <p>位置由「初始 pos + velocity·elapsed + 重力位移」在渲染时算（见 {@code SparkGeometry}），
 * 故 pos 存<b>初始位置</b>、velocity 为常量。三种来源由 {@link Kind} 区分，可独立开关与微调外观。
 * 与 {@link ArcEffect}/{@link BallEffect} 同构，走 {@code LightningRenderer.SPARKS} 队列。</p>
 */
public final class SparkEffect {

    public enum Kind { ENDPOINT, HIT, SURFACE }

    public final PositionSupplier pos;
    /** 世界空间速度（格/秒）；零向量 = 无方向纯爆点。 */
    public final Vec3 velocity;
    public final int seed;
    /** billboard 半边长（格）。ENDPOINT~0.04, HIT~0.12, SURFACE~0.03。 */
    public final float size;
    public final float maxLife;
    public float life;
    public final float r, g, b;
    public final Kind kind;
    /** 重力加速度（格/秒²，向下）。ENDPOINT/SURFACE~4，HIT~6。 */
    public final float gravity;

    public SparkEffect(PositionSupplier pos, Vec3 velocity, int seed, float size,
                       float life, float r, float g, float b, Kind kind, float gravity) {
        this.pos = pos;
        this.velocity = velocity;
        this.seed = seed;
        this.size = size;
        this.maxLife = life;
        this.life = life;
        this.r = r;
        this.g = g;
        this.b = b;
        this.kind = kind;
        this.gravity = gravity;
    }
}
