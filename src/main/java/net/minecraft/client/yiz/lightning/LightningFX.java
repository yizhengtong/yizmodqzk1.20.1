package net.minecraft.client.yiz.lightning;

import net.minecraft.client.yiz.lightning.fx.ArcEffect;
import net.minecraft.client.yiz.lightning.fx.SurfaceArcEffect;
import net.minecraft.client.yiz.lightning.render.LightningAirArcState;
import net.minecraft.client.yiz.lightning.render.LightningRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
/**
 * 闪电特效公开 API — 技能系统通过此入口 spawn 各类电弧/球/表面游离/感电视觉。
 *
 * <p><b>两类"贴着实体"的表现各自走哪条路（用户要求"空气电弧也要跟着模型轮廓"之后的定案）</b>：</p>
 * <ul>
 *   <li><b>挂在实体身上的弧</b>（环绕/体表）：一律走<b>模型几何镜像</b> ——
 *       几何 = 实体模型顶点双写，逐点贴合四肢/头部/盔甲轮廓。
 *       <ul>
 *         <li>{@link #spawnSurfaceArc(Entity)} / {@code spawnSurfaceArc(entity, life, ...)} →
 *             {@code AIR_ARC} 层（{@code LightningAirArcLayer}，短寿命游离弧，命中瞬间用）；</li>
 *         <li>持续感电（技能 APPLY）→ {@code LIGHTNING} 层（{@code LightningSurfaceLayer}，
 *             由 {@code ShockedEntityAPI} 驱动）。</li>
 *       </ul>
 *       旧通道 {@code SurfaceArcEffect}（按<b>包围盒六面</b>撒弧）<b>不再</b>用于实体 ——
 *       那正是"围着一个方盒子跳的电"。</li>
 *   <li><b>真正在空气里的弧</b>：{@link #spawnArc(Vec3, Vec3, float, float, float, float, float)}
 *       （两点之间的折线，含跟随实体的动态端点）与 {@link #spawnSurfaceArcOnBall}
 *       （球状闪电表面；球不是实体，没有模型可镜像）。这两类保留原实现。</li>
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
        ArcEffect e = new ArcEffect(from, to, life, width,
                ThreadLocalRandom.current().nextInt(), r, g, b);
        LightningRenderer.enqueue(e);
        return e;
    }

    /** 动态端点电弧：from/to 由 PositionSupplier 实时提供（渲染时 partialTick 插值，跟随移动目标不滞后）。 */
    public static ArcEffect spawnArc(net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier from,
                                     net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier to,
                                     float life, float width, float r, float g, float b) {
        ArcEffect e = new ArcEffect(from, to, life, width,
                ThreadLocalRandom.current().nextInt(), r, g, b);
        LightningRenderer.enqueue(e);
        return e;
    }

    /**
     * 对实体施加"围绕身体的游离电弧"（默认 0.35 秒、蓝白）。
     *
     * <p><b>几何 = 实体模型本身</b>（模型几何镜像的 {@code AIR_ARC} 层，见
     * {@code LightningAirArcLayer}）：弧贴着四肢/头部/盔甲的轮廓走，<b>不再是包围盒</b>。</p>
     *
     * @return 无（旧签名返回 {@code SurfaceArcEffect}；那个对象已经不存在了 —— 弧由镜像层按状态绘制）
     */
    public static void spawnSurfaceArc(Entity entity) {
        spawnSurfaceArc(entity, 0.35f, 0.028f, DEFAULT_R, DEFAULT_G, DEFAULT_B);
    }

    /**
     * 自定义外观的"围绕实体的游离电弧"。
     *
     * <p><b>改动说明（用户要求"空气电弧也应该跟着模型轮廓"）</b>：旧实现 new 一个
     * {@code SurfaceArcEffect} 入 {@code LightningRenderer.SURFACE} —— 那是按实体<b>包围盒六个面</b>
     * 生成小弧（还会生成跨面宽 40~80% 的长弧），会飘在头顶上方/脚下方这些模型之外的盒面上，
     * 看上去是"围着一个方盒子跳的电"。现在改成<b>注册状态</b>，由模型几何镜像的
     * {@code AIR_ARC} 层用实体模型自己的顶点画出来。</p>
     *
     * <p><b>仍然留在空气中的弧</b>（镜像层无法表达，故保留在 {@code LightningRenderer}）：</p>
     * <ul>
     *   <li>两点之间的链式电弧：{@link #spawnArc(net.minecraft.world.phys.Vec3, net.minecraft.world.phys.Vec3, float, float, float, float, float)}
     *       / 动态端点版本 —— 本来就是两点之间的折线，与模型轮廓无关；</li>
     *   <li>球状闪电表面：{@link #spawnSurfaceArcOnBall} —— 球不是实体（{@code BallEffect}），
     *       没有模型几何可镜像。</li>
     * </ul>
     */
    public static void spawnSurfaceArc(Entity entity, float life,
                                       float halfWidth, float r, float g, float b) {
        LightningAirArcState.put(entity, life, halfWidth, r, g, b);
    }

    /** 在指定位置 spawn 球状闪电。pos = 位置提供者（如头顶），selectable = 能否被链式选中作中转。 */
    public static net.minecraft.client.yiz.lightning.fx.BallEffect spawnBall(
            net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier pos, float radius, float life, boolean selectable) {
        var b = new net.minecraft.client.yiz.lightning.fx.BallEffect(pos, radius, life, selectable,
                ThreadLocalRandom.current().nextInt());
        LightningRenderer.enqueueBall(b);
        return b;
    }

    /**
     * 在动态球（如飞行球）表面生成游离电弧：AABB 实时跟随 center ± radius。
     *
     * <p><b>为什么这一种保留"包围盒/包围球"实现</b>：球状闪电不是实体（{@code BallEffect}），
     * 没有"模型几何"可以镜像 —— 它本来就是一个球，弧绕球面走是正确的表现。
     * 这是"无法用镜像层表达"的那一类，与实体身上的弧分开：见
     * {@link #spawnSurfaceArc(net.minecraft.world.entity.Entity, float, float, float, float, float)}。</p>
     */
    public static net.minecraft.client.yiz.lightning.fx.SurfaceArcEffect spawnSurfaceArcOnBall(
            net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier center, float radius, float life) {
        var e = new net.minecraft.client.yiz.lightning.fx.SurfaceArcEffect(center, radius, life, 0.025f,
                DEFAULT_R, DEFAULT_G, DEFAULT_B, ThreadLocalRandom.current().nextInt());
        LightningRenderer.enqueueSurface(e);
        return e;
    }

    // ══════════════════════════════════════════════════════════════════
    //  预设技能快捷函数（基于 LightningEmitter 编排层）
    // ══════════════════════════════════════════════════════════════════

    /** 技能 A：玩家头顶球状闪电 + 球持续向周围实体释放链式闪电。 */
    public static void skillA(net.minecraft.world.entity.player.Player player) {
        var head = net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier.offset(
                net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier.following(player),
                0, player.getBbHeight() + 1.0, 0);
        spawnBall(head, 0.5f, 20f, true);
        net.minecraft.client.yiz.lightning.orchestrate.LightningEmitter.builder(player.level())
                .source(head)
                .mode(net.minecraft.client.yiz.lightning.orchestrate.LightningEmitter.Mode.CONTINUOUS)
                .interval(0.5f)
                .range(8f).maxTargets(3).chainHops(3)
                .lifetime(20f)
                .start();
    }

    /** 技能 B：玩家前方定向电弧 → 命中目标后向周围扩散链式闪电。 */
    public static void skillB(net.minecraft.world.entity.player.Player player) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition(1f);
        net.minecraft.world.entity.Entity hit = raycastEntity(player, 16);
        if (hit == null) {
            spawnArc(eye, eye.add(player.getViewVector(1f).scale(16)));
            return;
        }
        net.minecraft.world.phys.Vec3 hitPos = new net.minecraft.world.phys.Vec3(
                hit.getX(), hit.getY() + hit.getBbHeight() * 0.5, hit.getZ());
        spawnArc(eye, hitPos, 0.4f, 0.05f, DEFAULT_R, DEFAULT_G, DEFAULT_B);
        net.minecraft.client.yiz.lightning.orchestrate.LightningEmitter.builder(player.level())
                .source(net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier.following(hit))
                .mode(net.minecraft.client.yiz.lightning.orchestrate.LightningEmitter.Mode.ONCE)
                .range(6f).maxTargets(4).chainHops(3)
                .lifetime(0.4f)
                .start();
    }

    /** 技能 C：范围技能，玩家周围所有实体各自触发链式闪电。 */
    public static void skillC(net.minecraft.world.entity.player.Player player) {
        net.minecraft.client.yiz.lightning.orchestrate.LightningEmitter.builder(player.level())
                .source(net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier.following(player))
                .mode(net.minecraft.client.yiz.lightning.orchestrate.LightningEmitter.Mode.ONCE)
                .range(8f).maxTargets(99)
                .chainHops(2)
                .lifetime(0.4f)
                .start();
    }

    /** 玩家视线方向的最近可拾取实体（技能 B 命中检测）。 */
    private static net.minecraft.world.entity.Entity raycastEntity(net.minecraft.world.entity.player.Player player, double range) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition(1f);
        net.minecraft.world.phys.Vec3 view = player.getViewVector(1f);
        net.minecraft.world.phys.AABB search = new net.minecraft.world.phys.AABB(eye, eye.add(view.scale(range))).inflate(1.0);
        return player.level().getEntities(player, search, e -> e.isAlive() && e.isPickable()).stream()
                .filter(e -> {
                    net.minecraft.world.phys.Vec3 toE = e.position().subtract(eye);
                    return toE.lengthSqr() > 1e-4 && toE.normalize().dot(view) > 0.92;
                })
                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(eye)))
                .orElse(null);
    }
}
