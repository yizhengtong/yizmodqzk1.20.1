package net.minecraft.client.yiz.lightning;

import net.minecraft.client.yiz.lightning.fx.ArcEffect;
import net.minecraft.client.yiz.lightning.render.LightningRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 闪电特效公开 API —— 只提供用户实测后确认要的两项。
 *
 * <h2>保留（用户定案：只有这两个有用）</h2>
 * <ul>
 *   <li><b>1 = 闪电链</b>：{@link #spawnArc(Vec3, Vec3, float, float, float, float, float)} 与
 *       {@link #spawnArc(net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier, net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier, float, float, float, float, float)}
 *       两个重载（后者端点由 {@code PositionSupplier} 实时插值，跟随移动目标不滞后）。
 *       门 = {@code FxDebugSwitches.chainArc()}（生成入口 + 渲染段兜底）。</li>
 *   <li><b>4 = 围弧</b>（用户要的"体表电流"）：{@code ShockEffects.shock(entity, 持续tick)} ——
 *       只登记"这个实体在感电"这一个状态；几何与渲染由 {@code ShockArcs} 负责：
 *       <b>独立的三维短弧</b>（飘在身体外 0.05~0.35 格那一圈空间里、有自己的顶点、有自己的生灭），
 *       端点取自模型顶点采出的锚点（{@code ShockAnchors}）
 *       ⇒ 模型被渲染到哪里，弧就在哪里：击飞倒下跟着倒、
 *       别的模组把模型挪到 10 格外也跟着飞。
 *       生产者两处：{@code S2CShockFxPayload} kind=0（整段感电）与 {@code LightningEmitter#fire}
 *       （命中瞬间 0.35s）：
 *       <pre>ShockEffects.shock(entity, lifeTicks);</pre>
 *       门 = {@code FxDebugSwitches.surfaceArcs()}。</li>
 * </ul>
 *
 * <h2>已删除（用户实测后确认无用，别再加回来）</h2>
 * <ul>
 *   <li><b>2 / 3</b>：模型几何镜像的 {@code LIGHTNING} / {@code AIR_ARC} 层 —— 都是一开（有实体感电时）
 *       就<b>整屏发黑</b>，两层与其状态表、生成入口（{@code spawnSurfaceArc(Entity…)} 两个重载）、
 *       着色器（{@code rendertype_lightning_surface.*} / {@code rendertype_lightning_modelarc.*}）
 *       全部删除。<b>黑屏根因写在 {@link LightningRenderer} 的类注释里</b>（缺 {@code "blend"} 节点 ⇒
 *       着色器自己 {@code disableBlend()}；外加用 {@code setShaderColor} 当强度通道）。
 *       <p><b>第四~六轮补充</b>：编号 4 曾经<b>确实</b>以镜像层的身份（{@code SHOCK} /
 *       {@code rendertype_shock_surface}）实现过 —— 它<b>不是</b>把 2/3 号接回来（JSON 有
 *       {@code "blend"} 节点、强度走顶点色 alpha、RenderType 与火焰层逐项同构、不绑纹理），
 *       也<b>确实没有黑屏</b>；但用户实测否掉了观感（"就是幽蓝色纹理贴在表面"）。
 *       第六轮因此把几何整个换掉（独立折线 + 顶点锚点），并删除了那一层与它的三个着色器资源。
 *       取证与结论见 {@code ModelMirrorLayers} 与 {@code ShockArcs} 的类注释 ——
 *       <b>结论：闪电类着色器不要再走模型几何镜像</b>。</p></li>
 *   <li><b>5</b>：旧体表叠亮（{@code LivingEntityRendererSurfaceLightningMixin} 把模型重画一遍）+
 *       其着色器 {@code rendertype_lightning_surface.*}。</li>
 *   <li><b>6</b>：球状闪电（{@code spawnBall} / {@code BallEffect} / {@code BallGeometry} / ball 着色器）。</li>
 *   <li><b>7</b>：电弧火花粒子（{@code SparkSpawner} / {@code SparkEffect} / {@code SparkGeometry} /
 *       {@code SparkConfig} / spark 着色器）。</li>
 *   <li><b>8</b>：{@code spawnSurfaceArcOnBall}（球面游离弧，与 6 号同批）。</li>
 *   <li><b>9</b>：被动带电指示的判定（{@code LightningSurfaceState}，喂给已删除的 5 号）。
 *       {@code SHOCK_ATTACK} / {@code SHOCK_DEFENSE} <b>属性本身保留</b>（注册在 {@code tizMod}），
 *       只是不再有客户端视觉消费者。</li>
 *   <li>{@code ShockedEntityAPI}（感电状态机）与 {@code skillA/B/C} 预设 —— 唯一消费者是 5 号，
 *       随 5 号一起删除。</li>
 * </ul>
 */
public final class LightningFX {

    /** 蓝白等离子默认色（线性 0..1）。 */
    public static final float DEFAULT_R = 0.40f, DEFAULT_G = 0.60f, DEFAULT_B = 1.00f;

    private LightningFX() {}

    public static ArcEffect spawnArc(Vec3 from, Vec3 to) {
        return spawnArc(from, to, 0.4f, 0.05f, DEFAULT_R, DEFAULT_G, DEFAULT_B);
    }

    public static ArcEffect spawnArc(Vec3 from, Vec3 to, float life, float width,
                                     float r, float g, float b) {
        // ── 开关 1（闪电链）：生成入口直接不生成（/yiz fxtoggle 1）──
        // 关掉后不仅新弧不再产生，已入队的弧也会在切换那一刻由 LightningRenderer.clearArcs() 清掉，
        // 于是"两实体之间的链式电弧"一个顶点都不画；围弧（开关 4）完全不受影响。
        if (!net.minecraft.client.yiz.tool.FxDebugSwitches.chainArc()) return null;
        ArcEffect e = new ArcEffect(from, to, life, width,
                ThreadLocalRandom.current().nextInt(), r, g, b);
        LightningRenderer.enqueue(e);
        return e;
    }

    /** 动态端点电弧：from/to 由 PositionSupplier 实时提供（渲染时 partialTick 插值，跟随移动目标不滞后）。 */
    public static ArcEffect spawnArc(net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier from,
                                     net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier to,
                                     float life, float width, float r, float g, float b) {
        // ── 开关 1（闪电链）：同上（这是链式电弧实际走的那条重载）──
        if (!net.minecraft.client.yiz.tool.FxDebugSwitches.chainArc()) return null;
        ArcEffect e = new ArcEffect(from, to, life, width,
                ThreadLocalRandom.current().nextInt(), r, g, b);
        LightningRenderer.enqueue(e);
        return e;
    }
}
