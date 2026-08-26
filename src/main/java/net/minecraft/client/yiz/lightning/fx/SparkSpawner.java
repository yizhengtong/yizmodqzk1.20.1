package net.minecraft.client.yiz.lightning.fx;

import net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier;
import net.minecraft.client.yiz.lightning.render.LightningRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 火花生成器 — 三种来源的 spawn 逻辑封装（节奏参数集中，便于调参）。
 *
 * <p>火花是电弧/球的<b>派生效果</b>（不由技能系统直接 spawn），由电弧端点持续迸发、
 * Emitter 命中瞬间迸发、SurfaceArc 表面溅射触发。统一入 {@code LightningRenderer.SPARKS} 队列。
 * 三种 {@link SparkEffect.Kind} 各自的寿命/大小/速度/重力分布定义在此。</p>
 */
public final class SparkSpawner {

    // ── ENDPOINT：电弧端点持续冒细火星，向下飘落 ──
    private static final float ENDPOINT_LIFE = 0.20f;
    private static final float ENDPOINT_SIZE = 0.045f;
    private static final float ENDPOINT_SPEED = 2.5f;
    private static final float ENDPOINT_GRAVITY = 4f;

    // ── HIT：命中瞬间一次性爆点，球面向外辐射 ──
    private static final float HIT_LIFE = 0.40f;
    private static final float HIT_SIZE = 0.12f;
    private static final float HIT_SPEED = 5.0f;
    private static final float HIT_GRAVITY = 6f;

    // ── SURFACE：表面游离溅射，小火星 ──
    private static final float SURFACE_LIFE = 0.15f;
    private static final float SURFACE_SIZE = 0.03f;
    private static final float SURFACE_SPEED = 1.0f;
    private static final float SURFACE_GRAVITY = 4f;

    private SparkSpawner() {}

    private static ThreadLocalRandom rnd() { return ThreadLocalRandom.current(); }

    /** 端点持续迸发：给 arc 的 from/to 各冒 1~2 颗细火星。 */
    public static void spawnEndpointSparks(Vec3 from, Vec3 to, int arcSeed, float r, float g, float b) {
        ThreadLocalRandom R = rnd();
        int n1 = 1 + R.nextInt(2);
        for (int i = 0; i < n1; i++) emitEndpoint(from, arcSeed * 31 + i, r, g, b, R);
        int n2 = 1 + R.nextInt(2);
        for (int i = 0; i < n2; i++) emitEndpoint(to, arcSeed * 79 + i, r, g, b, R);
    }

    private static void emitEndpoint(Vec3 at, int seed, float r, float g, float b, ThreadLocalRandom R) {
        // 随机方向 + 向下倾向（火星飘落感）
        Vec3 vel = randomDir(R)
                .scale(ENDPOINT_SPEED * (0.6 + R.nextDouble(0.4)))
                .add(0, -R.nextDouble(0.5), 0);
        LightningRenderer.enqueueSpark(new SparkEffect(
                PositionSupplier.fixed(at), vel, seed,
                ENDPOINT_SIZE * (0.8f + (float) R.nextDouble(0.4)),
                ENDPOINT_LIFE * (0.8f + (float) R.nextDouble(0.4)),
                r, g, b, SparkEffect.Kind.ENDPOINT, ENDPOINT_GRAVITY));
    }

    /** 命中瞬间迸发：在 hitPos 一次性向外球面辐射 count 颗大火星（count 上限 15 防失控）。 */
    public static void spawnHitBurst(Vec3 hitPos, int seed, float r, float g, float b, int count) {
        ThreadLocalRandom R = rnd();
        int n = Math.min(count, 15);
        for (int i = 0; i < n; i++) {
            Vec3 vel = randomDir(R).scale(HIT_SPEED * (0.5 + R.nextDouble(0.5)));
            LightningRenderer.enqueueSpark(new SparkEffect(
                    PositionSupplier.fixed(hitPos), vel, seed * 31 + i,
                    HIT_SIZE * (0.8f + (float) R.nextDouble(0.4)),
                    HIT_LIFE * (0.7f + (float) R.nextDouble(0.5)),
                    r, g, b, SparkEffect.Kind.HIT, HIT_GRAVITY));
        }
    }

    /** 表面游离溅射：在 at 处溅 1 颗小火星（沿随机法向）。 */
    public static void spawnSurfaceDrips(Vec3 at, int seed, float r, float g, float b) {
        ThreadLocalRandom R = rnd();
        Vec3 vel = randomDir(R).scale(SURFACE_SPEED * (0.5 + R.nextDouble(0.5)));
        LightningRenderer.enqueueSpark(new SparkEffect(
                PositionSupplier.fixed(at), vel, seed,
                SURFACE_SIZE * (0.8f + (float) R.nextDouble(0.4)),
                SURFACE_LIFE * (0.8f + (float) R.nextDouble(0.4)),
                r, g, b, SparkEffect.Kind.SURFACE, SURFACE_GRAVITY));
    }

    /** 单位球面随机方向（三轴 ± 随机后归一化的均匀近似）。 */
    private static Vec3 randomDir(ThreadLocalRandom R) {
        Vec3 v = new Vec3(R.nextDouble(-1, 1), R.nextDouble(-1, 1), R.nextDouble(-1, 1));
        double l = v.length();
        return l > 1e-4 ? v.scale(1.0 / l) : new Vec3(0, 1, 0);
    }
}
