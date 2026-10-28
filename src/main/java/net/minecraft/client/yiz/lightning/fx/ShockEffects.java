package net.minecraft.client.yiz.lightning.fx;

import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <b>感电体表围弧（编号 4）的状态表</b> —— 只回答"<b>谁</b>身上有电、<b>多亮</b>、<b>还有多久</b>"，
 * 几何完全由 {@link ShockArcs} 负责（独立的三维短弧，端点取自模型顶点采出的锚点）。
 *
 * <h2>为什么这里没有"围弧实例"这种几何对象</h2>
 * <p>本类从头到尾只有一个职责：<b>状态</b>。几何在第六轮定案为"独立折线 + 顶点锚点"
 * （见 {@link ShockArcs} 类注释）：</p>
 * <ul>
 *   <li>旧版（已删除的 {@code SurfaceArcEffect}）自己攒一组世界包围盒上的短线段，
 *       渲染时再复算姿态 —— 无论怎么换姿态基准都只能<b>逼近</b>模型的位置，
 *       连着出过"看不见""偏移到姥姥家"两次事故；</li>
 *   <li>第四/五轮把几何改成"实体模型顶点本身"（镜像层 {@code SHOCK}）—— 位置对了，
 *       但"哪里发光"由"哪块皮肤可见"决定 ⇒ 观感是贴在皮肤上的一层图案，被用户否掉；</li>
 *   <li>第六轮：几何回到"独立折线"（有自己的顶点、飘在体外），端点改挂到
 *       {@code ShockAnchors} 从模型顶点里采出的锚点上 —— 两头的好处都在，
 *       而本类依然只管状态。</li>
 * </ul>
 *
 * <h2>生产者（两处，都改成登记状态而非创建几何）</h2>
 * <ol>
 *   <li>{@code S2CShockFxPayload} kind=0：整段感电时长（{@code durationTicks}）；</li>
 *   <li>{@code LightningEmitter#fire}：命中瞬间的 0.35s。</li>
 * </ol>
 * <p>重复登记（连击/多段感电）走 {@link SurfaceShock#extend}：<b>续命 + 保持姿态</b>，
 * 不重新起手（避免"闪一下又从头淡入"的观感）。</p>
 *
 * <h2>线程</h2>
 * <p>登记/清除在客户端主线程（网络包的 {@code enqueueWork}、指令、tick），查询（{@link #shockOf}）
 * 也在客户端主线程 —— 原版 tick 与渲染是同一个主线程，{@link ShockArcs} 也一样。
 * 之所以仍用 {@code synchronized} 保护这一对列表操作：本类是全工程唯一"可能被别的线程碰到"的
 * 感电入口（历史原因留的保险），而条目数是个位数，代价可以忽略。</p>
 */
public final class ShockEffects {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("YizShock");

    private ShockEffects() {}

    /** 正在感电的实体状态（渲染 hook 每帧按实体查询）。 */
    private static final List<SurfaceShock> ACTIVE = new ArrayList<>();

    /**
     * 围弧的<b>历史</b>色档：弧心近白、外晕青蓝（最早那版围弧用的 0.4/0.6/1.0 同色系）。
     *
     * <p><b>围弧（编号 4）现在不再用这两个常量</b>：用户反馈"只有白蓝、缺明蓝"之后，
     * 颜色改成了 {@code ShockArcs} 里的<b>两档随机色表</b>（明蓝档 {@code COLOR_BLUE_*}
     * / 白蓝档 {@code COLOR_WB_*}，外加"核心偏白、光晕偏明蓝"的 {@code HALO_*} 光晕色，
     * 见 {@code ShockArcs#sampleColor}）。这里保留它们只为：① 其它闪电层/历史调用点的兼容；
     * ② 想快速回到"近白 + 青蓝"两色插值那一版观感时，把 {@code ShockArcs} 的色表临时
     * 指回这两个常量即可。本类<b>不</b>参与任何几何/顶点写入。</p>
     */
    public static final float CORE_R = 0.82f, CORE_G = 0.96f, CORE_B = 1.00f;
    public static final float EDGE_R = 0.40f, EDGE_G = 0.60f, EDGE_B = 1.00f;

    /**
     * 让某实体进入感电状态（已在感电则<b>续命</b>）。
     *
     * @param target     目标实体
     * @param lifeTicks  持续 tick（{@code <=0} 视为 1）
     * @return 本次登记后的状态（供调用方继续微调亮度/频率）
     */
    public static SurfaceShock shock(Entity target, int lifeTicks) {
        return shock(target, lifeTicks, 1.0f);
    }

    /**
     * 让某实体进入感电状态。
     *
     * @param target    目标实体
     * @param lifeTicks 持续 tick
     * @param intensity 整体强度倍率（1.0 = 标准；命中瞬间那种"更炸"的可以用 &gt;1）
     */
    public static SurfaceShock shock(Entity target, int lifeTicks, float intensity) {
        if (target == null) return null;
        synchronized (ACTIVE) {
            SurfaceShock existing = findLocked(target);
            if (existing != null) {
                existing.extend(lifeTicks);
                existing.setIntensity(intensity);
                return existing;
            }
            SurfaceShock s = new SurfaceShock(target, lifeTicks, intensity);
            ACTIVE.add(s);
            LOGGER.info("[Shock] 状态已创建: entity={} life={}t intensity={} 当前感电实体数={}",
                    net.minecraft.client.yiz.modelmirror.MirrorDiag.entity(target),
                    s.maxLife, intensity, ACTIVE.size());
            return s;
        }
    }

    /**
     * 查询某实体的感电状态（渲染 hook 每帧查一次）。
     *
     * @return 该实体的状态；没有则 {@code null}
     */
    public static SurfaceShock shockOf(Entity entity) {
        if (entity == null) return null;
        synchronized (ACTIVE) {
            return findLocked(entity);
        }
    }

    /** <b>必须</b>在持有 {@link #ACTIVE} 锁时调用（同一实体对象用引用比较，避免 equals 带来的意外匹配）。 */
    private static SurfaceShock findLocked(Entity entity) {
        for (int i = 0; i < ACTIVE.size(); i++) {
            if (ACTIVE.get(i).target == entity) return ACTIVE.get(i);
        }
        return null;
    }

    /** 清空全部感电状态（{@code /yiz fxtoggle 4} 关掉时调用 ⇒ "完全不画"立刻生效）。 */
    public static void clear() {
        synchronized (ACTIVE) {
            ACTIVE.clear();
        }
    }

    /** 当前感电实体数（诊断用）。 */
    public static int activeCount() {
        synchronized (ACTIVE) {
            return ACTIVE.size();
        }
    }

    /** 客户端每 tick 调用（见 {@code ShaderRenderRegistrar}）：推进寿命、回收到期的。 */
    public static void tick() {
        synchronized (ACTIVE) {
            for (int i = ACTIVE.size() - 1; i >= 0; i--) {
                SurfaceShock s = ACTIVE.get(i);
                s.tick();
                if (s.finished()) ACTIVE.remove(i);
            }
        }
    }

    /** 只读视图（诊断/调试用；渲染路径请用 {@link #shockOf}）。 */
    public static List<SurfaceShock> active() {
        synchronized (ACTIVE) {
            return Collections.unmodifiableList(new ArrayList<>(ACTIVE));
        }
    }

    /**
     * 单个实体的感电状态。
     *
     * <p><b>亮度/闪烁全在这里算</b>，每帧一次，然后作为一个标量乘进每条弧的 alpha
     * （{@link ShockArcs#emit} 里 {@code 弧自身寿命曲线 × 逐条随机亮度 × 本强度}）。
     * 这样"闪烁"的节奏由 <b>tick</b> 决定而不是由帧率决定：
     * 同一次感电在 60fps 与 240fps 下看着一样"噼啪"，不会因为帧多就闪得更快。</p>
     */
    public static final class SurfaceShock {

        /** 淡入 tick（避免"啪"地出现）。 */
        private static final int FADE_IN = 2;
        /** 淡出 tick（{@code stop()} 后按它收尾）。 */
        private static final int FADE_OUT = 6;
        /** 整体呼吸/闪烁的 tick 周期与幅度（电弧的"明暗起伏"）。 */
        private static final int FLICKER_PERIOD = 3;

        private final Entity target;
        /** 初始寿命（tick）：只用于日志与"这是第几次登记"。 */
        private final int maxLife;

        private int age;
        /** 剩余寿命（tick）；{@code life<=0} 时给一个极大值 = 不自动结束。 */
        private int remaining;
        private boolean ended;
        private float intensity;

        /** 每帧算好的最终强度（0~1.6）与闪烁系数（0~1），{@code prepare} 时算一次给整帧用。 */
        private float frameStrength = 1.0f;
        private float frameFlicker = 1.0f;

        SurfaceShock(Entity target, int lifeTicks, float intensity) {
            this.target = target;
            this.maxLife = Math.max(1, lifeTicks);
            this.remaining = lifeTicks > 0 ? lifeTicks : Integer.MAX_VALUE;
            this.intensity = intensity;
        }

        /** 供渲染层取目标实体。 */
        public Entity target() {
            return target;
        }

        /** 续命（重复登记）：不重置 age，于是不会重新淡入 —— 观感是"电一直在跳"。 */
        void extend(int lifeTicks) {
            if (lifeTicks <= 0) return;
            this.ended = false;
            this.remaining = Math.max(this.remaining, lifeTicks);
        }

        void setIntensity(float i) {
            this.intensity = Math.max(0.0f, i);
        }

        /** 整体淡入淡出（0~1）：顶点色 alpha 用。 */
        public float alpha() {
            float in = FADE_IN <= 0 ? 1.0f : Math.min(1.0f, age / (float) FADE_IN);
            float out = FADE_OUT <= 0 ? 1.0f : Math.min(1.0f, remaining / (float) FADE_OUT);
            return Math.max(0.0f, Math.min(in, out));
        }

        /**
         * 每帧强度（0~1.6）：{@code alpha × intensity × 闪烁}。
         *
         * <p>这就是乘进每条弧最终 alpha 的那个数（见 {@link ShockArcs#emit}）。</p>
         *
         * <p><b>为什么按 tick 算而不是按帧算</b>：{@code ShockArcs.emit} 每个实体每帧只调一次，
         * 所以这里每帧进一次；但相位取自 {@code tickCount}（客户端游戏刻）而不是渲染帧号 ——
         * 否则同样的感电在 240fps 下会比 60fps 下闪得快 4 倍。{@code tickCount} 由实体给出
         * （{@code Entity#tickCount}），不额外维护计数器。</p>
         */
        public float strength(int tickCount) {
            float p1 = phase(tickCount + age, FLICKER_PERIOD);
            float p2 = phase(tickCount + age, 2);
            frameFlicker = Math.max(0.0f, Math.min(1.0f, 0.55f + 0.45f * p1 * 0.6f + 0.45f * p2 * 0.4f));
            frameStrength = Math.max(0.0f, Math.min(1.6f, alpha() * intensity * (0.45f + 0.85f * frameFlicker)));
            return frameStrength;
        }

        /** {@link #strength(int)} 算出来的闪烁系数（诊断用）。 */
        public float flicker() {
            return frameFlicker;
        }

        /** 平滑的 0..1 方波（周期 {@code period} tick），相位取 {@code t}。 */
        private static float phase(int t, int period) {
            if (period <= 0) return 1.0f;
            float x = (t % period) / (float) period;
            // 三角波再平滑一次：不是硬开关，像"亮度起伏"
            float tri = x < 0.5f ? x * 2.0f : (1.0f - x) * 2.0f;
            return tri * tri * (3.0f - 2.0f * tri);
        }

        /** 结束（进入淡出）。 */
        public void stop() {
            if (ended) return;
            ended = true;
            remaining = Math.min(remaining, FADE_OUT);
        }

        boolean finished() {
            return ended && remaining <= 0;
        }

        void tick() {
            if (!target.isAlive() || target.isRemoved()) {
                stop();
                // 实体没了：立刻回收（不必等淡出，反正没有几何可画）
                remaining = 0;
                return;
            }
            age++;
            remaining--;
            if (remaining <= 0) ended = true;
        }
    }
}
