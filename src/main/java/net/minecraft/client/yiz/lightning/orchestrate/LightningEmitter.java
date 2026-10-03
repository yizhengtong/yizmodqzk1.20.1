package net.minecraft.client.yiz.lightning.orchestrate;

import net.minecraft.client.yiz.lightning.LightningFX;
import net.minecraft.client.yiz.lightning.render.LightningRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 闪电编排发射器 — 把原子特效（电弧/球）组合成完整技能效果。
 *
 * <p>{@link Mode#ONCE} 创建即发射一次；{@link Mode#CONTINUOUS} 每 {@code interval} 秒发射一次直到寿命结束。
 * <b>链式</b>：{@code chainHops>0} 时，每个命中目标处递归 spawn 一个子 Emitter（{@code chainHops-1}，
 * 继承 visited 已访问集合防环）。一行 builder 即可拼出技能 A/B/C。</p>
 */
public class LightningEmitter {

    public enum Mode { ONCE, CONTINUOUS }

    private final PositionSupplier origin;
    private final Level level;
    private final TargetSelector selector;
    private final Mode mode;
    private final float interval;
    private final int chainHops, maxTargets;
    private final float range;
    private final float maxLife;
    private final float r, g, b, halfWidth;
    private final Set<Integer> visited;
    private float life;
    private float tickAccum;
    private boolean fired;

    LightningEmitter(PositionSupplier origin, Level level, TargetSelector selector, Mode mode,
                     float interval, int chainHops, int maxTargets, float range, float lifetime,
                     float r, float g, float b, float halfWidth, Set<Integer> visited) {
        this.origin = origin;
        this.level = level;
        this.selector = selector;
        this.mode = mode;
        this.interval = interval;
        this.chainHops = chainHops;
        this.maxTargets = maxTargets;
        this.range = range;
        this.maxLife = lifetime;
        this.life = lifetime;
        this.r = r;
        this.g = g;
        this.b = b;
        this.halfWidth = halfWidth;
        this.visited = visited;
    }

    /** 每 tick 调用：按 mode 发射 + 衰减寿命；返回 false 表示应移除。 */
    public boolean tick(float dt) {
        if (level == null) return false;
        if (mode == Mode.ONCE) {
            if (!fired) { fire(); fired = true; }
        } else {
            tickAccum += dt;
            if (tickAccum >= interval) { fire(); tickAccum -= interval; }
        }
        life -= dt;
        return life > 0f;
    }

    private void fire() {
        Vec3 o = origin.get(0f);
        List<Entity> targets = selector.select(level, o, range, maxTargets);
        for (Entity t : targets) {
            if (visited.contains(t.getId())) continue;
            PositionSupplier hit = PositionSupplier.offset(
                    PositionSupplier.following(t), 0, t.getBbHeight() * 0.5, 0);
            LightningFX.spawnArc(origin, hit, 0.35f, halfWidth, r, g, b);
            // 命中瞬间"挂在目标身上"的围弧 —— <b>编号 4</b>，与 S2CShockFxPayload kind=0 同一条通道
            // （ShockEffects 的状态表），门也是同一个开关（FxDebugSwitches.surfaceArcs()，<b>默认开</b>）。
            //
            // 几何 = 独立的三维短弧（飘在目标身体外那一圈空间里），端点取自<b>实体自己被渲染时写出的
            // 那批顶点</b>采出的锚点（ShockArcs + ShockAnchors）⇒ 命中即挂上去的这批弧同样
            // "模型在哪它就在哪"：击飞倒下跟着倒，被别的模组挪走也跟着飞。
            // 这里用的是命中瞬间的短寿命（0.35s = 7 tick）；命中之后"整段感电时长"的围弧由
            // S2CShockFxPayload kind=0 另外登记（同一个状态表，重复登记走 extend 续命，不重新淡入）。
            if (net.minecraft.client.yiz.tool.FxDebugSwitches.surfaceArcs()) {
                net.minecraft.client.yiz.lightning.fx.ShockEffects.shock(t, 7, 1.25f);
            }
            visited.add(t.getId());
            if (chainHops > 0) {
                LightningEmitter child = new LightningEmitter(
                        PositionSupplier.following(t), level, selector, Mode.ONCE, 0f,
                        chainHops - 1, maxTargets, range, 0.3f,
                        r, g, b, halfWidth, new HashSet<>(visited));
                LightningRenderer.enqueueEmitter(child);
            }
        }
    }

    public static Builder builder(Level level) { return new Builder(level); }

    /** 流畅构造器：技能系统用它组装 Emitter 并 start()。 */
    public static final class Builder {
        private final Level level;
        private PositionSupplier origin;
        private TargetSelector selector = TargetSelector.nearby();
        private Mode mode = Mode.ONCE;
        private float interval = 0.25f;
        private int chainHops = 0;
        private int maxTargets = 3;
        private float range = 8f;
        private float lifetime = 0.4f;
        private float r = LightningFX.DEFAULT_R, g = LightningFX.DEFAULT_G, b = LightningFX.DEFAULT_B;
        private float halfWidth = 0.045f;

        Builder(Level level) { this.level = level; }

        public Builder source(PositionSupplier o) { this.origin = o; return this; }
        public Builder selector(TargetSelector s) { this.selector = s; return this; }
        public Builder mode(Mode m) { this.mode = m; return this; }
        public Builder interval(float i) { this.interval = i; return this; }
        public Builder chainHops(int h) { this.chainHops = h; return this; }
        public Builder maxTargets(int m) { this.maxTargets = m; return this; }
        public Builder range(float rng) { this.range = rng; return this; }
        public Builder lifetime(float l) { this.lifetime = l; return this; }
        public Builder color(float cr, float cg, float cb) { this.r = cr; this.g = cg; this.b = cb; return this; }
        public Builder halfWidth(float w) { this.halfWidth = w; return this; }

        public LightningEmitter start() {
            LightningEmitter e = new LightningEmitter(origin, level, selector, mode, interval,
                    chainHops, maxTargets, range, lifetime, r, g, b, halfWidth, new HashSet<>());
            LightningRenderer.enqueueEmitter(e);
            return e;
        }
    }
}
