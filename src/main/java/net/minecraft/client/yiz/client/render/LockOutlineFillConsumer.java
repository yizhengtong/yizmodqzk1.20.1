package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.vertex.DefaultedVertexConsumer;
import com.mojang.blaze3d.vertex.VertexConsumer;

import javax.annotation.Nullable;

/**
 * 描边填充的顶点消费者：<b>覆盖顶点色为描边色</b> + <b>按该层贴图的 alpha 做 CPU 侧镂空</b>。
 *
 * <p>顶点色部分复刻 vanilla {@code OutlineBufferSource.EntityOutlineGenerator}（保留全部 NEW_ENTITY 属性），
 * 理由与坑（{@code super.defaultColor(...)} 必须绕过本类的空实现）见构造器注释。</p>
 *
 * <h2>镂空：为什么在 CPU 侧，以及"面"是怎么认定的</h2>
 * <ul>
 *   <li><b>面 = 每 4 次 {@code endVertex()}</b>。填充 RenderType 是
 *       {@code VertexFormat.Mode.QUADS}（{@link LockOutlineRenderType#fill}），而 QUADS 的顶点流里
 *       每 4 个顶点就是一个四边形 —— 调用点也确实是这么写的：{@code ModelPart.Cube.compile}
 *       逐 {@code Polygon} 写它的 4 个顶点（{@code ModelPart.java:283-296}），
 *       {@code Polygon} 的顶点数组长度恒为 4（{@code ModelPart.java:306}）。
 *       ⇒ 只有攒满 4 个顶点才决定"整面留还是整面丢"，<b>绝不会写出半个面</b>
 *       （半个面会让后面所有面整体错位，比不镂空严重得多）。</li>
 *   <li>本类把"该层贴图的 alpha 位图"（{@link LockOutlineAlphaMask}）交给三个判定：
 *       ① 面的 UV 包围盒里<b>一个可见纹素都没有</b> ⇒ 整面丢弃（正是"无纹理部位被整体描边"的来源：
 *          零厚度薄片的背面、只有透明填充的区域）；② 包围盒全是不透明纹素 ⇒ 原样输出（零改动快路径）；
 *       ③ 混合 ⇒ <b>把面按纹素连通段切成若干子面</b>，每个子面的 UV 只覆盖不透明纹素。</li>
 *   <li>第 ③ 步是"逐片元镂空"的 CPU 等价物：子面的 4 个角点由原面 4 个角点按 UV 双线性插值得到
 *       （模型面是平面矩形，双线性在 UV 与位置之间是精确的），因此子面并集 == 原面上
 *       "UV 落在不透明纹素里"的那部分面积。即使 GPU 侧的 {@code discard} 因为任何原因不生效，
 *       描边掩码也已经是镂空的。反过来若 GPU 侧也生效，两者结论一致（子面内部不会再被切掉）。</li>
 *   <li><b>一切失败都退回现状</b>：贴图没解析到 / 没拿到 alpha 位图 / UV 越界或退化 /
 *       连通段数或子面预算超限 ⇒ 该面原样输出（= 改造前的整面填充）。</li>
 * </ul>
 *
 * <h2>不碰任何全局渲染状态</h2>
 * <p>本类只在"写顶点"这条纯 CPU 路径上做判断与插值：不调 {@code RenderSystem} 的任何方法、
 * 不设采样器、不改混合/深度/剔除/渲染目标。它写出的是<b>普通的新顶点</b>，
 * 与改造前的唯一区别是"少写了一些顶点、多写了一些 UV 更小的顶点"。</p>
 */
final class LockOutlineFillConsumer extends DefaultedVertexConsumer {

    /** 单个消费者（= 一层 × 一个实体）允许切出的最大子面数；超出后剩下的面整面输出（有界兜底）。 */
    private static final int MAX_SUBQUADS_PER_CONSUMER = 1024;

    /** UV 轴对齐判定的容差（见 {@link #roles}）：远小于一个纹素在 UV 上的宽度，又远大于 float 误差。 */
    private static final float UV_AXIS_EPS = 1.0E-4F;

    private final VertexConsumer delegate;
    /** 该层贴图的 alpha 位图；{@code null} = 不做 CPU 镂空（退回现状）。 */
    @Nullable
    private final LockOutlineAlphaMask mask;

    // ── 当前顶点的暂存（与改造前完全一致）──
    private double x;
    private double y;
    private double z;
    private float u;
    private float v;
    private int overlayU;
    private int overlayV;
    private int lightU;
    private int lightV;
    private float nx;
    private float ny;
    private float nz;
    private boolean uvSeen;

    // ── 一个面的 4 个顶点（QUADS：每 4 次 endVertex 恰好一个面）──
    private final double[] px = new double[4];
    private final double[] py = new double[4];
    private final double[] pz = new double[4];
    private final float[] pu = new float[4];
    private final float[] pv = new float[4];
    private final float[] pnx = new float[4];
    private final float[] pny = new float[4];
    private final float[] pnz = new float[4];
    private final int[] pOverlayU = new int[4];
    private final int[] pOverlayV = new int[4];
    private final int[] pLightU = new int[4];
    private final int[] pLightV = new int[4];
    private final boolean[] pUvSeen = new boolean[4];
    private int count;

    // ── 复用缓冲（每帧零分配）──
    private final int[] box = new int[4];
    private final int[] roleOf = new int[4];
    private final int[] roleIndex = new int[4];
    private final boolean[] roleUsed = new boolean[4];

    private int subQuadBudget = MAX_SUBQUADS_PER_CONSUMER;

    // ── 统计（只用于诊断日志）──
    private int statFaces;
    private int statDroppedFaces;
    private int statSubQuads;

    LockOutlineFillConsumer(VertexConsumer delegate, float[] color, @Nullable LockOutlineAlphaMask mask) {
        this.delegate = delegate;
        this.mask = mask;
        // ⚠️ 必须是 {@code super.defaultColor(...)}：本类把 {@code defaultColor} 重写成了空实现
        //    （为了挡住 VertexMultiConsumer 的广播覆盖），写 {@code this.defaultColor(...)} 会**虚分派到
        //    那个空实现**，于是颜色一个字都进不去，{@code defaultR/G/B/A} 保持 DefaultedVertexConsumer 的
        //    初值 255,255,255,255 —— 填充 FBO 里就是**不透明白色**：Provider 给的描边色（锁定 = 青色）
        //    与 alpha（= 充能进度）双双失效，后处理 `fragColor = vec4(c.rgb, c.a * edge)` 于是变成
        //    "白色 × 1.0" ⇒ density≈0.5 的区域**满不透明**地糊在屏幕上（充能多少都一样亮）。
        //    vanilla 的 EntityOutlineGenerator 构造器同样写的是 super.defaultColor(...)
        //    （OutlineBufferSource.java:69-71），正是为了绕过它自己那个空实现。
        super.defaultColor(
            (int) (color[0] * 255f), (int) (color[1] * 255f),
            (int) (color[2] * 255f), (int) (color[3] * 255f));
    }

    /**
     * <b>必须屏蔽</b>：{@code VertexMultiConsumer} 会把 {@code defaultColor(...)} 广播给每个下游
     * consumer（{@code VertexMultiConsumer.java:85-93}），而 {@code DefaultedVertexConsumer.defaultColor}
     * 会覆盖构造器里设好的 {@code defaultR/G/B/A} —— 于是"描边色"会被实体自己的默认色冲掉
     * （vanilla 的 {@code EntityOutlineGenerator} 同样把这两个方法写成空实现，
     * {@code OutlineBufferSource.java:66-70}，理由一致）。描边色只能来自本构造函数那一次设置
     * ——注意那次设置走的是 {@code super.defaultColor(...)}，不经过本方法。
     */
    @Override
    public void defaultColor(int r, int g, int b, int a) {}

    /** 同上：屏蔽"清默认色"（否则 endVertex 写出的颜色仍是构造器那份，语义上不该被外部改）。 */
    @Override
    public void unsetDefaultColor() {}

    // ══════════════════════════ 顶点摄取（与改造前一致） ══════════════════════════

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.uvSeen = false;      // 新顶点开始：uv() 是否来过由它自己决定
        return this;
    }

    @Override
    public VertexConsumer color(int r, int g, int b, int a) {
        return this;              // 忽略原色，用描边色
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        this.u = u;
        this.v = v;
        this.uvSeen = true;
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        this.overlayU = u;
        this.overlayV = v;
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        this.lightU = u;
        this.lightV = v;
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        this.nx = x;
        this.ny = y;
        this.nz = z;
        return this;
    }

    @Override
    public void endVertex() {
        if (this.mask == null) {
            emitCurrent();
            return;
        }
        // 攒面：QUADS 的顶点流每 4 个一个面
        this.px[this.count] = this.x;
        this.py[this.count] = this.y;
        this.pz[this.count] = this.z;
        this.pu[this.count] = this.u;
        this.pv[this.count] = this.v;
        this.pnx[this.count] = this.nx;
        this.pny[this.count] = this.ny;
        this.pnz[this.count] = this.nz;
        this.pOverlayU[this.count] = this.overlayU;
        this.pOverlayV[this.count] = this.overlayV;
        this.pLightU[this.count] = this.lightU;
        this.pLightV[this.count] = this.lightV;
        this.pUvSeen[this.count] = this.uvSeen;
        this.count++;
        if (this.count == 4) {
            emitFace();
            this.count = 0;
        }
    }

    /**
     * 把"攒了但不满 4 个"的那几个顶点原样写出去（{@link LockOutlineBufferSource} 会在刷帧/换层前调用）。
     *
     * <p>合法几何永远是 4 的倍数（见类注释），所以正常情况下这里是空操作；
     * 留着它是为了"万一某个 mod 写了非 4 倍数顶点"，也不至于把整段几何丢掉。</p>
     */
    void flushResidue() {
        for (int i = 0; i < this.count; i++) emitSlot(i);
        this.count = 0;
    }

    int statFaces() {
        return this.statFaces;
    }

    int statDroppedFaces() {
        return this.statDroppedFaces;
    }

    int statSubQuads() {
        return this.statSubQuads;
    }

    // ══════════════════════════ 整面判定 ══════════════════════════

    private void emitFace() {
        this.statFaces++;
        if (!this.pUvSeen[0] || !this.pUvSeen[1] || !this.pUvSeen[2] || !this.pUvSeen[3]) {
            emitWholeFace();
            return;                                   // 没见过 uv（非 NEW_ENTITY 语义）⇒ 不过滤
        }
        float uMin = this.pu[0];
        float uMax = this.pu[0];
        float vMin = this.pv[0];
        float vMax = this.pv[0];
        for (int i = 1; i < 4; i++) {
            if (this.pu[i] < uMin) uMin = this.pu[i];
            if (this.pu[i] > uMax) uMax = this.pu[i];
            if (this.pv[i] < vMin) vMin = this.pv[i];
            if (this.pv[i] > vMax) vMax = this.pv[i];
        }
        if (!this.mask.texelBox(uMin, vMin, uMax, vMax, this.box)) {
            emitWholeFace();
            return;                                   // UV 越界 / 退化 ⇒ 不过滤
        }
        int x0 = this.box[0];
        int y0 = this.box[1];
        int x1 = this.box[2];
        int y1 = this.box[3];
        int[] rects = this.mask.rectsFor(x0, y0, x1, y1);
        if (rects == null || rects.length == 0) {
            if (rects == null) {
                emitWholeFace();                      // 连通段超限 ⇒ 整面回退
            } else {
                this.statDroppedFaces++;              // ★ 整面没有任何可见纹素 ⇒ 丢
            }
            return;
        }
        if (rects.length == 4 && rects[0] == x0 && rects[1] == x1 && rects[2] == y0 && rects[3] == y1) {
            emitWholeFace();                          // 整面可见纹素 ⇒ 原样（零改动快路径）
            return;
        }
        int rectCount = rects.length / 4;
        if (rectCount > this.subQuadBudget) {
            emitWholeFace();                          // 子面预算用尽 ⇒ 整面回退（有界）
            return;
        }
        int[] role = roles(uMin, uMax, vMin, vMax);
        if (role == null) {
            emitWholeFace();                          // 角点映射不成立（非常规四边形）⇒ 不过滤
            return;
        }
        this.subQuadBudget -= rectCount;
        this.statSubQuads += rectCount;
        float umW = uMin * this.mask.width();
        float vmH = vMin * this.mask.height();
        float duW = (uMax - uMin) * this.mask.width();
        float dvH = (vMax - vMin) * this.mask.height();
        for (int r = 0; r < rectCount; r++) {
            int xa = rects[r * 4];
            int xb = rects[r * 4 + 1];
            int ya = rects[r * 4 + 2];
            int yb = rects[r * 4 + 3];
            float s0 = (xa - umW) / duW;
            float s1 = (xb + 1 - umW) / duW;
            float t0 = (ya - vmH) / dvH;
            float t1 = (yb + 1 - vmH) / dvH;
            emitSubQuad(s0, s1, t0, t1, uMin, vMin, uMax, vMax, this.roleOf);
        }
    }

    /**
     * 角点角色映射：给原面的 4 个顶点各定一个角色
     * （bit0 = u 取大端，bit1 = v 取大端 ⇒ 0=(uMin,vMin) 1=(uMax,vMin) 2=(uMin,vMax) 3=(uMax,vMax)）。
     *
     * <p>返回 {@code null} 表示映射不成立 ⇒ 调用方不过滤。两种不成立的情形：</p>
     * <ol>
     *   <li>两个顶点落在同一个角色（UV 不成矩形）；</li>
     *   <li>同一角色的顶点 UV <b>并不相等</b>（UV 被旋转/斜切过）——这时"角点双线性"不再等于真实的
     *       UV→位置映射，切出来的子面会落到错误的位置。判据是"同 u 端必须同 u、同 v 端必须同 v"，
     *       标准实体模型（{@code ModelPart.Cube}/{@code Polygon} 的轴对齐 UV 矩形）永远满足。</li>
     * </ol>
     *
     * <p>镜像面（{@code .mirror()}）在这里自动成立：角色只由 UV 决定，位置随便怎么翻都能被双线性插值覆盖。</p>
     */
    @Nullable
    private int[] roles(float uMin, float uMax, float vMin, float vMax) {
        float um = (uMin + uMax) * 0.5F;
        float vm = (vMin + vMax) * 0.5F;
        this.roleUsed[0] = this.roleUsed[1] = this.roleUsed[2] = this.roleUsed[3] = false;
        for (int i = 0; i < 4; i++) {
            int role = (this.pu[i] > um ? 1 : 0) | (this.pv[i] > vm ? 2 : 0);
            if (this.roleUsed[role]) return null;
            this.roleUsed[role] = true;
            this.roleOf[i] = role;
            this.roleIndex[role] = i;
        }
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                if ((this.roleOf[i] & 1) == (this.roleOf[j] & 1)
                        && Math.abs(this.pu[i] - this.pu[j]) > UV_AXIS_EPS) {
                    return null;
                }
                if ((this.roleOf[i] & 2) == (this.roleOf[j] & 2)
                        && Math.abs(this.pv[i] - this.pv[j]) > UV_AXIS_EPS) {
                    return null;
                }
            }
        }
        return this.roleOf;
    }

    /** 子面：UV 边界 [s0,s1]×[t0,t1]（相对本面的 UV 矩形），角点位置由 4 个原角点双线性插值。 */
    private void emitSubQuad(float s0, float s1, float t0, float t1,
                             float uMin, float vMin, float uMax, float vMax, int[] role) {
        int i00 = this.roleIndex[0];
        int i10 = this.roleIndex[1];
        int i01 = this.roleIndex[2];
        int i11 = this.roleIndex[3];
        float du = uMax - uMin;
        float dv = vMax - vMin;
        for (int k = 0; k < 4; k++) {
            // 按原面的顶点顺序输出 ⇒ 子面绕序与原面一致
            int r = role[k];
            double s = (r & 1) != 0 ? s1 : s0;
            double t = (r & 2) != 0 ? t1 : t0;
            double w00 = (1.0 - s) * (1.0 - t);
            double w10 = s * (1.0 - t);
            double w01 = (1.0 - s) * t;
            double w11 = s * t;
            double ox = w00 * this.px[i00] + w10 * this.px[i10] + w01 * this.px[i01] + w11 * this.px[i11];
            double oy = w00 * this.py[i00] + w10 * this.py[i10] + w01 * this.py[i01] + w11 * this.py[i11];
            double oz = w00 * this.pz[i00] + w10 * this.pz[i10] + w01 * this.pz[i01] + w11 * this.pz[i11];
            float onx = (float) (w00 * this.pnx[i00] + w10 * this.pnx[i10] + w01 * this.pnx[i01] + w11 * this.pnx[i11]);
            float ony = (float) (w00 * this.pny[i00] + w10 * this.pny[i10] + w01 * this.pny[i01] + w11 * this.pny[i11]);
            float onz = (float) (w00 * this.pnz[i00] + w10 * this.pnz[i10] + w01 * this.pnz[i01] + w11 * this.pnz[i11]);
            int li = this.roleIndex[r];   // 光照/覆层取"这个角色那个角点"的值（同一面的 4 个顶点本来就一致）
            this.delegate.vertex(ox, oy, oz)
                .color(defaultR, defaultG, defaultB, defaultA)
                .uv(uMin + (float) s * du, vMin + (float) t * dv)
                .overlayCoords(this.pOverlayU[li], this.pOverlayV[li])
                .uv2(this.pLightU[li], this.pLightV[li])
                .normal(onx, ony, onz)
                .endVertex();
        }
    }

    // ══════════════════════════ 写出 ══════════════════════════

    /** 原样输出整个面（= 改造前的行为）。 */
    private void emitWholeFace() {
        for (int i = 0; i < 4; i++) emitSlot(i);
    }

    private void emitCurrent() {
        this.delegate.vertex(this.x, this.y, this.z)
            .color(defaultR, defaultG, defaultB, defaultA)
            .uv(this.u, this.v)
            .overlayCoords(this.overlayU, this.overlayV)
            .uv2(this.lightU, this.lightV)
            .normal(this.nx, this.ny, this.nz)
            .endVertex();
    }

    private void emitSlot(int i) {
        this.delegate.vertex(this.px[i], this.py[i], this.pz[i])
            .color(defaultR, defaultG, defaultB, defaultA)
            .uv(this.pu[i], this.pv[i])
            .overlayCoords(this.pOverlayU[i], this.pOverlayV[i])
            .uv2(this.pLightU[i], this.pLightV[i])
            .normal(this.pnx[i], this.pny[i], this.pnz[i])
            .endVertex();
    }
}
