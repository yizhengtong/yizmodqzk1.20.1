package net.minecraft.client.yiz.lightning;

import net.minecraft.client.yiz.lightning.fx.ArcEffect;
import net.minecraft.client.yiz.lightning.fx.SurfaceArcEffect;
import net.minecraft.client.yiz.lightning.render.LightningRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 闪电特效公开 API — 技能系统通过此入口 spawn 各类电弧/球/表面游离/感电视觉。
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

    /** 对实体施加表面缠绕电弧（默认 20 秒、蓝白）。 */
    public static net.minecraft.client.yiz.lightning.fx.SurfaceArcEffect spawnSurfaceArc(net.minecraft.world.entity.Entity entity) {
        return spawnSurfaceArc(entity, 20f, 0.028f, DEFAULT_R, DEFAULT_G, DEFAULT_B);
    }

    /** 自定义外观对实体施加表面缠绕电弧。 */
    public static net.minecraft.client.yiz.lightning.fx.SurfaceArcEffect spawnSurfaceArc(net.minecraft.world.entity.Entity entity, float life,
                                                                                          float halfWidth, float r, float g, float b) {
        var e = new net.minecraft.client.yiz.lightning.fx.SurfaceArcEffect(entity, life, halfWidth, r, g, b,
                ThreadLocalRandom.current().nextInt());
        LightningRenderer.enqueueSurface(e);
        return e;
    }

    /** 在指定位置 spawn 球状闪电。pos = 位置提供者（如头顶），selectable = 能否被链式选中作中转。 */
    public static net.minecraft.client.yiz.lightning.fx.BallEffect spawnBall(
            net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier pos, float radius, float life, boolean selectable) {
        var b = new net.minecraft.client.yiz.lightning.fx.BallEffect(pos, radius, life, selectable,
                ThreadLocalRandom.current().nextInt());
        LightningRenderer.enqueueBall(b);
        return b;
    }

    /** 在动态球（如飞行球）表面生成游离电弧：AABB 实时跟随 center ± radius。 */
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
