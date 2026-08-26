package net.minecraft.client.yiz.lightning.fx;

import net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier;

/**
 * 球状闪电 — billboard 等离子球。
 *
 * <p>三角色由字段表达：
 * <ul>
 *   <li>纯特效：{@code selectable=false}（不挂 emitter，里程碑4 接入）</li>
 *   <li>伤害源：挂 {@code emitter}（里程碑4 加该字段）</li>
 *   <li>链式中转：{@code selectable=true}（里程碑4 selector 候选集）</li>
 * </ul>
 * 位置由 {@link PositionSupplier} 提供 → 可悬浮头顶（跟随玩家+偏移）、自由飞行等。</p>
 */
public final class BallEffect {
    public final PositionSupplier pos;
    public final float radius;
    public final float maxLife;
    public float life;
    public final boolean selectable;
    public final int seed;

    public BallEffect(PositionSupplier pos, float radius, float life, boolean selectable, int seed) {
        this.pos = pos;
        this.radius = radius;
        this.maxLife = life;
        this.life = life;
        this.selectable = selectable;
        this.seed = seed;
    }
}
