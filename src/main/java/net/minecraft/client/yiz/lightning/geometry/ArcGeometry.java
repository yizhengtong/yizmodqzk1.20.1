package net.minecraft.client.yiz.lightning.geometry;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.yiz.lightning.util.ShaderTimeUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * 电弧几何生成 — 「外辉光 + 主干 + 细丝分支」三层，主干/细丝用<b>交叉带（cross-billboard）</b>。
 *
 * <p><b>活的电流</b>：折线是<b>锯齿形</b>（每个内部顶点随机方向偏移 → 相邻顶点连线成锐角转弯，
 * 闪电的标志形态，非平滑波浪）；偏移在两个随机布局间由 {@code iTime} 平滑插值 → 连续跃动
 * 而非整体闪烁；端点固定（{@code sin(t·π)} 让抖动在中段最强、两端归零）。</p>
 *
 * <p><b>端点弧度</b>：带宽按位置 taper 渐细（两端→0、中段满宽），避免平截面，呈现尖锐纺锤形。</p>
 *
 * <p><b>立体感</b>：每条带画两份，宽度轴 a0 朝相机、a1 与 a0 正交，从任意视角至少一条带以合理宽度
 * 面对玩家 → 等离子柱的 3D 体积感。所有坐标为相机相对坐标（相机在原点，视线 = -点位置）。</p>
 */
public final class ArcGeometry {

    /** 主干折线段数。 */
    public static final int SEGMENTS = 16;

    private ArcGeometry() {}

    private static Vec3 perpendicular(Vec3 d) {
        Vec3 ref = Math.abs(d.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        return d.cross(ref).normalize();
    }

    /** 点 i 处的（单位）段方向，端点用单侧差分。 */
    private static Vec3 segDir(Vec3[] pts, int i) {
        int n = pts.length;
        Vec3 sd;
        if (i == 0) sd = pts[1].subtract(pts[0]);
        else if (i == n - 1) sd = pts[n - 1].subtract(pts[n - 2]);
        else sd = pts[i + 1].subtract(pts[i - 1]);
        double sl = sd.length();
        return sl > 1e-6 ? sd.scale(1.0 / sl) : new Vec3(0, 1, 0);
    }

    /**
     * 计算每个点垂直于段方向的「宽度轴」。
     * @param axis 0 = 朝相机的 billboard 轴（a0 = sd×view）；1 = 与 a0 正交的第二轴（a1 = sd×a0）
     */
    private static Vec3[] widthAxes(Vec3[] pts, int axis) {
        int n = pts.length;
        Vec3[] axes = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Vec3 sd = segDir(pts, i);
            Vec3 view = pts[i].scale(-1.0); // 相机在原点 → 视线 = -点位置
            Vec3 a0 = sd.cross(view);
            if (a0.lengthSqr() < 1e-10) a0 = perpendicular(sd);
            a0 = a0.normalize();
            axes[i] = axis == 0 ? a0 : sd.cross(a0).normalize();
        }
        return axes;
    }

    /**
     * 沿 from→to 生成<b>锯齿折线</b>（闪电的锐角转弯，非平滑波浪）。
     *
     * <p>每个内部顶点在垂直平面内随机方向偏移 → 相邻顶点连线形成锐角锯齿；偏移在两个随机布局
     * 间由 {@code time} 平滑插值 → 连续跃动而非整体闪烁；端点固定（{@code sin(t·π)} 让偏移在
     * 中段最强、两端归零）。</p>
     */
    private static Vec3[] buildSpine(Vec3 fromRel, Vec3 toRel, double amp, long seed, float time, int segments) {
        Vec3 dir = toRel.subtract(fromRel);
        Vec3 dN = dir.length() > 1e-6 ? dir.scale(1.0 / dir.length()) : new Vec3(0, 1, 0);
        Vec3 perp1 = perpendicular(dN);
        Vec3 perp2 = dN.cross(perp1).normalize();
        double speed = 6.0;                                  // 每秒 6 个新锯齿布局目标
        int bucket = (int) Math.floor(time * speed);
        double frac = time * speed - bucket;
        double f = frac * frac * (3 - 2 * frac);             // smoothstep，起停更自然
        Vec3[] a = zigzagLayout(seed, bucket, fromRel, dir, amp, perp1, perp2, segments);
        Vec3[] b = zigzagLayout(seed, bucket + 1, fromRel, dir, amp, perp1, perp2, segments);
        Vec3[] pts = new Vec3[segments + 1];
        for (int i = 0; i <= segments; i++) {
            pts[i] = a[i].add(b[i].subtract(a[i]).scale(f));
        }
        return pts;
    }

    /** 主干用默认 {@link #SEGMENTS} 段，保持现有调用点（emitArc/分支）零改动。 */
    private static Vec3[] buildSpine(Vec3 fromRel, Vec3 toRel, double amp, long seed, float time) {
        return buildSpine(fromRel, toRel, amp, seed, time, SEGMENTS);
    }

    /** 单个锯齿布局：内部顶点随机方向偏移（端点固定，偏移随 t 中段最强）。 */
    private static Vec3[] zigzagLayout(long seed, int bucket, Vec3 fromRel, Vec3 dir, double amp,
                                       Vec3 perp1, Vec3 perp2, int segments) {
        Random rnd = new Random(seed ^ ((long) bucket * 0x9E3779B97F4A7C15L));
        Vec3[] pts = new Vec3[segments + 1];
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            Vec3 p = fromRel.add(dir.scale(t));
            pts[i] = p;
            if (i == 0 || i == segments) continue;          // 端点固定
            double taper = Math.sin(t * Math.PI);
            double ang = rnd.nextDouble() * 6.2831853;
            double mag = (0.15 + rnd.nextDouble() * 1.15) * amp * taper;
            pts[i] = p.add(perp1.scale(Math.cos(ang) * mag)).add(perp2.scale(Math.sin(ang) * mag));
        }
        return pts;
    }

    /** 把折线通胀成 ribbon 带写入 vc；带宽按位置 taper（两端渐细，避免平截面）。 */
    private static void emitBand(VertexConsumer vc, Matrix4f mat, Vec3[] pts, Vec3[] axes,
                                 float halfWidth, float r, float g, float b, float alpha) {
        int n = pts.length;
        for (int i = 0; i < n - 1; i++) {
            float tA = (float) i / (n - 1);
            float tB = (float) (i + 1) / (n - 1);
            float wA = halfWidth * taperWidth(tA);
            float wB = halfWidth * taperWidth(tB);
            Vec3 pA = pts[i], pB = pts[i + 1];
            Vec3 na = axes[i], nb = axes[i + 1];
            Vec3 aL = pA.add(na.scale(wA)), aR = pA.subtract(na.scale(wA));
            Vec3 bL = pB.add(nb.scale(wB)), bR = pB.subtract(nb.scale(wB));
            vert(vc, mat, aL, tA,  1f, r, g, b, alpha);
            vert(vc, mat, bL, tB,  1f, r, g, b, alpha);
            vert(vc, mat, bR, tB, -1f, r, g, b, alpha);
            vert(vc, mat, aR, tA, -1f, r, g, b, alpha);
        }
    }

    /** 带宽 taper：端点宽度→0（收成真针尖，消除"被截断"的平头），中段满宽；e 控制收尖区长度。 */
    private static float taperWidth(float t) {
        float e = 0.15f;
        return Math.min(smooth(0, e, t), smooth(0, e, 1 - t));
    }

    private static float smooth(float e0, float e1, float x) {
        float u = (float) net.minecraft.util.Mth.clamp((x - e0) / (e1 - e0), 0.0, 1.0);
        return u * u * (3 - 2 * u);
    }

    /** 在折线上按参数 t∈[0,1] 线性插值取点。 */
    private static Vec3 sampleSpine(Vec3[] pts, double t) {
        int last = pts.length - 1;
        double x = net.minecraft.util.Mth.clamp(t, 0.0, 1.0) * last;
        int i = (int) Math.floor(x);
        if (i >= last) return pts[last];
        double f = x - i;
        return pts[i].add(pts[i + 1].subtract(pts[i]).scale(f));
    }

    /**
     * 生成完整电弧（外辉光 + 主干双交叉带 + 细丝分支双交叉带）写入 vc。
     *
     * @param halfWidth 主干半宽（格）；辉光自动 ×3.6，细丝自动 ×0.4
     */
    /**
     * 用给定 spine（相机相对坐标）渲染完整电弧：外辉光 + 主干双交叉带 + 细丝分支。
     * spine 来源不限（空气电弧的 buildSpine，或表面附着的游走轨迹），通用入口。
     */
    public static void emitArcFromSpine(VertexConsumer vc, Matrix4f mat, Vec3[] spine, int seed,
                                        float halfWidth, float r, float g, float b, float alpha, float time) {
        Vec3[] ax0 = widthAxes(spine, 0);
        Vec3[] ax1 = widthAxes(spine, 1);

        // 1. 外辉光带（单带朝相机）
        emitBand(vc, mat, spine, ax0, halfWidth * 3.6f, r * 0.45f, g * 0.55f, b, alpha * 0.20f);
        // 2. 主干双交叉带
        emitBand(vc, mat, spine, ax0, halfWidth, r, g, b, alpha * 0.75f);
        emitBand(vc, mat, spine, ax1, halfWidth, r, g, b, alpha * 0.75f);

        // 3. 二级锯齿（分形层级）：主干每相邻两点递归一层细锯齿(amp 0.05/4 子段)，逼近真实闪电层次
        for (int i = 0; i < spine.length - 1; i++) {
            Vec3[] sub = buildSpine(spine[i], spine[i + 1], 0.05, seed * 7L + i, time, 4);
            Vec3[] sx0 = widthAxes(sub, 0);
            emitBand(vc, mat, sub, sx0, halfWidth * 0.7f, r * 0.9f, g * 0.95f, b, alpha * 0.5f);
        }

        // 4. 细丝分支（基于 spine 首尾方向）
        Vec3 s0 = spine[0], s1 = spine[spine.length - 1];
        Vec3 dir = s1.subtract(s0);
        double len = dir.length();
        if (len < 0.5) return;
        Vec3 dN = dir.scale(1.0 / len);
        Vec3 perp1 = perpendicular(dN);
        Vec3 perp2 = dN.cross(perp1).normalize();
        Random rnd = new Random(seed);
        int branches = 6 + rnd.nextInt(5); // 6~10 条（更密更蓬）
        for (int k = 0; k < branches; k++) {
            double t0 = 0.10 + rnd.nextDouble() * 0.65;
            double t1 = Math.min(0.95, t0 + 0.20 + rnd.nextDouble() * 0.25);
            Vec3 p0 = sampleSpine(spine, t0);
            Vec3 p1 = sampleSpine(spine, t1);
            double sx = (rnd.nextDouble() - 0.5) * 0.30;
            double sy = (rnd.nextDouble() - 0.5) * 0.30;
            Vec3 off = perp1.scale(sx).add(perp2.scale(sy));
            long bseed = seed * 31L + k + 7;
            Vec3[] branch = buildSpine(p0.add(off), p1.add(off.scale(0.5)), 0.10, bseed, time);
            Vec3[] bx0 = widthAxes(branch, 0);
            Vec3[] bx1 = widthAxes(branch, 1);
            float bw = halfWidth * 0.4f;
            float ba = alpha * 0.6f;
            emitBand(vc, mat, branch, bx0, bw, r * 0.7f, g * 0.8f, b, ba);
            emitBand(vc, mat, branch, bx1, bw, r * 0.7f, g * 0.8f, b, ba);
        }
    }

    /** 空气电弧 A→B：buildSpine 生成锯齿 spine 后复用 {@link #emitArcFromSpine}。 */
    public static void emitArc(VertexConsumer vc, Matrix4f mat,
                               Vec3 fromRel, Vec3 toRel, int seed,
                               float halfWidth, float r, float g, float b, float alpha) {
        float time = ShaderTimeUtil.now();
        Vec3[] spine = buildSpine(fromRel, toRel, 0.22, seed, time);
        emitArcFromSpine(vc, mat, spine, seed, halfWidth, r, g, b, alpha, time);
    }

    /** 短弧细丝（分支）的折线段数：4 段 ⇒ 单根 4×4=16 个顶点（很便宜，可以挂在很多条弧上）。 */
    public static final int SHORT_ARC_FILAMENT_SEGMENTS = 4;

    /**
     * <b>短弧段</b>（围弧 / 编号 4 用）：<b>同一套</b>锯齿折线 + 宽度轴 + taper 带，
     * 但段数可调、<b>不生成细丝分支与二级锯齿</b>（{@link #emitShortArc(VertexConsumer, Matrix4f, Vec3, Vec3, int, float, float, float, float, float, float, int, int)}
     * 可以按需挂 0~N 根细丝）。
     *
     * <p><b>为什么必须另开一个入口</b>：{@link #emitArcFromSpine} 的顶点开销是"每条弧
     * 主干 16 段 ×3 带 + 16 段二级锯齿 + 6~10 条细丝分支（每条又 16 段 ×2 带）"≈ 1500 个顶点，
     * 那是给"跨越几十格的链式闪电"用的。围弧是"每条实体挂 20~40 多条、长度只有 0.1~0.6 格"的
     * 小弧，照这个量算就是几万个顶点/实体/帧。折成 {@code segments} 段并去掉二级锯齿后，
     * 单条弧 = {@code 12 × segments} 个顶点（{@code segments}=6 时 72 个；{@code segments}=5 时 60 个），
     * 量级对得上，观感（锯齿 + 双交叉带 + 外辉光 + 两端收尖）与编号 1 完全一致。</p>
     *
     * @param segments 折线段数（围弧用 6；越大越连续也越贵）
     * @param time     {@link ShaderTimeUtil#now()} 的当帧值（一次绘制只取一次，逐弧共用）
     */
    public static void emitShortArc(VertexConsumer vc, Matrix4f mat,
                                    Vec3 fromRel, Vec3 toRel, int seed, float halfWidth,
                                    float r, float g, float b, float alpha, float time, int segments) {
        emitShortArc(vc, mat, fromRel, toRel, seed, halfWidth, r, g, b, alpha, time, segments, 0);
    }

    /**
     * 同上，并可挂 {@code filaments} 根<b>细丝分支</b>（围弧"电弧丛"观感用的那一层）。
     *
     * <p>细丝复用编号 1 里已经验证过的现成做法（{@code emitArcFromSpine} 的"4. 细丝分支"）：
     * 在主干上取一点 → 沿垂直平面随机方向挑出去一段 → 用 {@link #buildSpine} 生成一条更短、
     * 更细、更暗的锯齿带。差别只有两点（都是为了围弧的预算）：</p>
     * <ul>
     *   <li>段数固定 {@link #SHORT_ARC_FILAMENT_SEGMENTS}（4 段，16 顶点/根）；
     *   <li>只画<b>一条</b>朝相机的带（不画交叉带）—— 细丝本来就只有一两像素宽，
     *       交叉带看不出来还翻倍开销。</li>
     * </ul>
     *
     * <p>顶点预算：主干 {@code 3 × segments × 4}（{@code segments}=6 → 72）+ 每根细丝 16。
     * 于是"挂 2 根细丝"的最坏单弧 = 104 顶点，40 多条弧 ≈ 4.6k 顶点/实体/帧（见
     * {@code ShockArcs} 顶部的预算表）。</p>
     *
     * @param filaments 细丝根数（0 = 不挂；围弧按概率给 0~2）
     */
    public static void emitShortArc(VertexConsumer vc, Matrix4f mat,
                                    Vec3 fromRel, Vec3 toRel, int seed, float halfWidth,
                                    float r, float g, float b, float alpha, float time, int segments,
                                    int filaments) {
        // 短弧的抖动幅度按"弧长"给：0.22（链式闪电那档）会把 0.3 格的弧拧成毛线团
        Vec3[] spine = buildSpine(fromRel, toRel, 0.09, seed, time, segments);
        Vec3[] ax0 = widthAxes(spine, 0);
        Vec3[] ax1 = widthAxes(spine, 1);
        // 外辉光（朝相机的单带，颜色偏冷偏暗）+ 主干双交叉带（3D 体积感），配方与 emitArcFromSpine 同源
        emitBand(vc, mat, spine, ax0, halfWidth * 3.0f, r * 0.45f, g * 0.55f, b, alpha * 0.20f);
        emitBand(vc, mat, spine, ax0, halfWidth, r, g, b, alpha * 0.80f);
        emitBand(vc, mat, spine, ax1, halfWidth, r, g, b, alpha * 0.80f);

        if (filaments <= 0) return;
        Vec3 s0 = spine[0], s1 = spine[spine.length - 1];
        Vec3 dir = s1.subtract(s0);
        double len = dir.length();
        if (len < 1.0e-4) return;                        // 退化：两端重合，不挂细丝（免得 NaN）
        Vec3 dN = dir.scale(1.0 / len);
        Vec3 perp1 = perpendicular(dN);
        Vec3 perp2 = dN.cross(perp1).normalize();
        Random rnd = new Random(seed * 0x9E3779B97F4A7C15L + 17L);
        for (int k = 0; k < filaments; k++) {
            double t0 = 0.15 + rnd.nextDouble() * 0.55;  // 从主干中段挑出去（两端收尖处挑出来会像断线）
            Vec3 p0 = sampleSpine(spine, t0);
            double flen = len * (0.35 + rnd.nextDouble() * 0.30);   // 细丝长度 = 弧长的 35%~65%
            double ang = rnd.nextDouble() * 6.2831853;
            Vec3 off = perp1.scale(Math.cos(ang)).add(perp2.scale(Math.sin(ang)));
            // 往"侧面 + 略微顺着主干"的方向挑：纯垂直会像梳齿，带一点前倾才像分叉的电弧
            Vec3 p1 = p0.add(off.scale(flen)).add(dN.scale(flen * (0.15 + rnd.nextDouble() * 0.35)));
            Vec3[] fil = buildSpine(p0, p1, 0.06, seed * 131L + k * 7L + 3L, time,
                    SHORT_ARC_FILAMENT_SEGMENTS);
            Vec3[] fx = widthAxes(fil, 0);
            emitBand(vc, mat, fil, fx, halfWidth * 0.45f, r * 0.75f, g * 0.85f, b, alpha * 0.55f);
        }
    }

    private static void vert(VertexConsumer vc, Matrix4f m, Vec3 p,
                             float u, float v, float r, float g, float b, float a) {
                vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).uv(u, v)
            .color((int)(r*255), (int)(g*255), (int)(b*255), (int)(a*255)).endVertex();
    }
}
