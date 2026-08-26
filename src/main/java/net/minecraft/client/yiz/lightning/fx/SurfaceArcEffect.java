package net.minecraft.client.yiz.lightning.fx;

import net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 表面游离电弧 — 在 AABB 表面循环生成短寿命小电弧（游离态闪电群）。
 *
 * <p>两种 AABB 来源：
 * <ul>
 *   <li>Entity 模式：跟随实体包围盒（自适应大小 + 跟随移动，目标失效自动移除）</li>
 *   <li>球模式：AABB 实时 = center ± radius（给飞行/悬浮球加表面游离电弧）</li>
 * </ul>
 * 每条小电弧两端在同一表面、跨度为该面尺寸的 40~80%，独立短寿命（0.15~0.3s），不断生灭。</p>
 *
 * <p><b>实时跟随</b>：{@link #getBox(float)} 用 {@code partialTick} 插值包围盒位置
 * （Entity 模式跟随实体插值位置、球模式跟随 center.get(pt)），渲染时据此重算小电弧端点，
 * 消除「体表电弧跟着实体上一 tick 位置」的 1 tick 滞后。</p>
 */
public final class SurfaceArcEffect {

    /** Entity 模式时的目标（球模式为 null）。 */
    public final Entity target;
    public final float maxLife;
    public float life;
    public final float halfWidth;
    public final float r, g, b;
    public final int seed;

    public final CopyOnWriteArrayList<MiniArc> arcs = new CopyOnWriteArrayList<>();

    /** 球模式中心（Entity 模式为 null）。 */
    private final PositionSupplier center;
    /** 球模式半径。 */
    private final double radius;
    private final Random rnd;
    private static final int SPAWN_PER_TICK = 5;
    private static final float MINI_LIFE_BASE = 0.15f;
    private static final float MINI_LIFE_VAR = 0.20f;

    /** Entity 模式：AABB 跟随 target（插值中心 + 实时尺寸）。 */
    public SurfaceArcEffect(Entity target, float life, float halfWidth, float r, float g, float b, int seed) {
        this.target = target;
        this.center = null;
        this.radius = 0;
        this.maxLife = life; this.life = life;
        this.halfWidth = halfWidth; this.r = r; this.g = g; this.b = b; this.seed = seed;
        this.rnd = new Random(seed);
    }

    /** 球模式：AABB 实时 = center ± radius（跟随球的 PositionSupplier）。 */
    public SurfaceArcEffect(PositionSupplier center, float radius, float life, float halfWidth, float r, float g, float b, int seed) {
        this.target = null;
        this.center = center;
        this.radius = radius;
        this.maxLife = life; this.life = life;
        this.halfWidth = halfWidth; this.r = r; this.g = g; this.b = b; this.seed = seed;
        this.rnd = new Random(seed);
    }

    /** 实时包围盒（partialTick 插值位置 + 实时尺寸）。渲染用 pt，tick 用 0f。 */
    public AABB getBox(float pt) {
        if (target != null) {
            double x = target.xOld + (target.getX() - target.xOld) * pt;
            double y = target.yOld + (target.getY() - target.yOld) * pt;
            double z = target.zOld + (target.getZ() - target.zOld) * pt;
            AABB base = target.getBoundingBox();
            double cx = (base.minX + base.maxX) * 0.5 - target.getX();
            double cy = (base.minY + base.maxY) * 0.5 - target.getY();
            double cz = (base.minZ + base.maxZ) * 0.5 - target.getZ();
            double hx = (base.maxX - base.minX) * 0.5;
            double hy = (base.maxY - base.minY) * 0.5;
            double hz = (base.maxZ - base.minZ) * 0.5;
            return new AABB(x + cx - hx, y + cy - hy, z + cz - hz, x + cx + hx, y + cy + hy, z + cz + hz);
        }
        Vec3 c = center.get(pt);
        return new AABB(c.x - radius, c.y - radius, c.z - radius, c.x + radius, c.y + radius, c.z + radius);
    }

    public boolean tick(float densityFactor) {
        if (target != null && (!target.isAlive() || target.isRemoved())) return false;
        AABB box = getBox(0f);
        // 密度控制：距离公式 × 密度公式 决定每 tick 生成数量（LOD）
        int spawn = Math.max(1, (int) Math.round(SPAWN_PER_TICK * densityFactor * distanceFactor()));
        for (int i = 0; i < spawn; i++) arcs.add(spawnMini(box));
        arcs.removeIf(m -> {
            m.life -= 0.05f;
            return m.life <= 0f;
        });
        life -= 0.05f;
        return life > 0f;
    }

    /** 距离公式：体表电流距离相机越远，生成密度越低（远距 LOD）。 */
    private float distanceFactor() {
        if (target == null) return 1.0f;  // 球模式无实体，不适用距离 LOD
        var cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double dx = target.getX() - cam.x;
        double dy = target.getY() - cam.y;
        double dz = target.getZ() - cam.z;
        double distSqr = dx * dx + dy * dy + dz * dz;
        if (distSqr < 256.0) return 1.0f;     // <16 格全密度
        if (distSqr > 4096.0) return 0.15f;   // >64 格最低密度
        double dist = Math.sqrt(distSqr);
        return (float)(1.0 - (dist - 16.0) / 48.0 * 0.85);  // 16~64 格线性衰减 1.0→0.15
    }

    private MiniArc spawnMini(AABB box) {
        int face = rnd.nextInt(6);
        double ua = rnd.nextDouble(), va = rnd.nextDouble();
        double ang = rnd.nextDouble() * 6.2831853;
        double span = 0.55 + rnd.nextDouble() * 0.40;
        double ub = net.minecraft.util.Mth.clamp(ua + Math.cos(ang) * span, 0, 1);
        double vb = net.minecraft.util.Mth.clamp(va + Math.sin(ang) * span, 0, 1);
        Vec3 from = faceToWorld(box, face, ua, va);
        Vec3 to = faceToWorld(box, face, ub, vb);
        float ml = MINI_LIFE_BASE + rnd.nextFloat() * MINI_LIFE_VAR;
        return new MiniArc(from, to, face, ua, va, ub, vb, rnd.nextInt(), ml);
    }

    private static Vec3 faceToWorld(AABB box, int face, double u, double v) {
        double mnX = box.minX, mxX = box.maxX, mnY = box.minY, mxY = box.maxY, mnZ = box.minZ, mxZ = box.maxZ;
        double x, y, z;
        switch (face) {
            case 0:  x = mnX + u * (mxX - mnX); y = mnY;               z = mnZ + v * (mxZ - mnZ); break;
            case 1:  x = mnX + u * (mxX - mnX); y = mxY;               z = mnZ + v * (mxZ - mnZ); break;
            case 2:  x = mnX;                  y = mnY + u * (mxY - mnY); z = mnZ + v * (mxZ - mnZ); break;
            case 3:  x = mxX;                  y = mnY + u * (mxY - mnY); z = mnZ + v * (mxZ - mnZ); break;
            case 4:  x = mnX + u * (mxX - mnX); y = mnY + v * (mxY - mnY); z = mnZ;               break;
            default: x = mnX + u * (mxX - mnX); y = mnY + v * (mxY - mnY); z = mxZ;               break;
        }
        return new Vec3(x, y, z);
    }

    /** 一条表面游离小电弧（短 A→B，独立寿命）。 */
    public static final class MiniArc {
        public final Vec3 from, to;   // 初始快照（供 tick 内火花等使用）
        public final int face;        // 表面参数化（供渲染时插值重算端点）
        public final double ua, va, ub, vb;
        public final int seed;
        public final float maxLife;
        public float life;

        MiniArc(Vec3 from, Vec3 to, int face, double ua, double va, double ub, double vb, int seed, float life) {
            this.from = from;
            this.to = to;
            this.face = face;
            this.ua = ua; this.va = va; this.ub = ub; this.vb = vb;
            this.seed = seed;
            this.maxLife = life;
            this.life = life;
        }

        /** 按插值包围盒重算起点（实时跟随）。 */
        public Vec3 fromAt(AABB box) { return faceToWorld(box, face, ua, va); }
        /** 按插值包围盒重算终点（实时跟随）。 */
        public Vec3 toAt(AABB box) { return faceToWorld(box, face, ub, vb); }
    }
}
