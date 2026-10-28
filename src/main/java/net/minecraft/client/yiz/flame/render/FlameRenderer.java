package net.minecraft.client.yiz.flame.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.yiz.flame.FlameEffects;
import net.minecraft.client.yiz.lightning.util.ShaderTimeUtil;
import net.minecraft.client.yiz.modelmirror.RenderStateSnapshot;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 体积火焰渲染器：把 {@link FlameEffects} 里的效果画成"包围盒 + 射线步进"的体积。
 *
 * <p><b>几何怎么来</b>：每种形态只算一个保守 AABB（比真实火焰体略大，真正的形状由片元着色器的
 * {@code shapeMask} 裁），CPU 侧没有任何逐样本工作，视觉密度全在着色器里。</p>
 *
 * <p><b>单遍绘制 + 着色器内深度截断</b>：包围盒的 6 个面一次全发，<b>不写也不测</b>硬件深度。</p>
 * <ul>
 *   <li>覆盖完整性：凸盒在任意相机位置（盒外 / 盒内 / 半进半出）都保证"每像素至少一个面覆盖"，
 *       体积永远不会因面选择而缺角或整团消失；</li>
 *   <li>遮挡：盒子面深度没有物理意义，拿它做硬件深度测试会让"火与方块相交"变成"只剩边界、
 *       内部透空"（方块背后的盒面被剔除，而那里本该有火）。改由片元着色器在射线步进里
 *       <b>逐样本比对场景深度</b>（{@code Sampler0} = 主目标深度纹理）：采样点跑到地形后面就停止积分 ——
 *       火被方块正确截断、被墙整段遮住，而盒面本身一定能进光栅化。</li>
 *   <li>代价：盒外看会多合成一次（入口 + 出口），用 {@link #VOLUME_COMPOSITE_COMPENSATION} 补密度；
 *       另外每个采样点多一次深度纹理取样（相对 3D simplex 可忽略）。</li>
 * </ul>
 * <p>踩过的坑：① <b>不要用"深度预 pass"</b>——它必须 depthMask(true)，会把体积入口面写进全局主深度缓冲，
 * 同帧里先画的效果会挡住后面的效果（表现为"只有特定角度/特定一个效果看得到"）；
 * ② <b>不要拿盒面做硬件深度测试</b>——见上（"接触方块只剩边界"）。</p>
 *
 * <p><b>两种锚定方式</b>（见 {@link FlameEffects.Anchor}）：</p>
 * <ul>
 *   <li>{@code WORLD} 客观世界坐标固定 —— 位置、轴向、噪声全部钉死在世界里；</li>
 *   <li>{@code ENTITY} 追踪实体实时位置 —— 每帧取实体插值位置作锚点，几何轴向与噪声采样
 *       都转到实体局部坐标（{@code followYaw} 打开时还会跟着实体转身）。</li>
 * </ul>
 *
 * <p><b>坐标系</b>：与 {@code LightningRenderer} 一致，顶点用<b>相机相对世界坐标</b>
 * （{@code worldPos - cameraPos}），因此 {@code ModelViewMat}（来自 RenderLevelStage 的 PoseStack）
 * 只含视图旋转，相机在该空间里位于原点（{@code uCameraWorld} 恒传 0）；噪声靠 {@code uCamWorld}
 * 加回世界位置才锚定在世界（或实体）上，否则相机一动火焰纹理就会游动。</p>
 *
 * <p><b>每效果一次 draw call</b>：uniform 是逐效果的，无法批处理；开销主要在片元侧的
 * {@code STEPS × OCTAVES} 次 3D simplex，draw call 数量不是瓶颈。</p>
 */
public final class FlameRenderer {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("YizFlame");

    /**
     * 密度补偿：6 面全发时，相机在盒外会对同一像素合成两次（入口 + 出口各一次积分），
     * 观感偏浓；这里把密度整体乘一个 &lt;1 的系数补回来。
     */
    private static final float VOLUME_COMPOSITE_COMPENSATION = 0.65f;

    /**
     * 整体透明度缩放：越小越透（最终密度 = {@code Flame.density × 上面两项}）。
     * 想整体调透/调浓只改这一个数；单件特效仍可用 {@code Flame.density} 单独调。
     */
    private static final float OPACITY_SCALE = 0.55f;

    /** 本效果本帧的屏幕投影尺寸（像素），供 LOD 判据复用（避免每效果算两遍）。 */
    private static float projectedPx;

    private FlameRenderer() {}

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** 复用的 AABB 暂存（[0..2]=min, [3..5]=max，相机相对）；渲染线程单线程访问，零分配。 */
    private static final float[] BOX = new float[6];
    /** 复用的火绳点暂存（世界坐标 → 每帧算一次，bounds 与 uniform 共用）。 */
    private static final Vec3[] ROPE_WORLD = new Vec3[8];

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        // 惰性解析：RegisterShadersEvent 期间 event.registerShader 只是排队，实例要等编译完才可取
        ShaderInstance shader = FlameShaders.flame();
        if (shader == null) return;

        List<FlameEffects.Flame> flames = FlameEffects.active();
        if (flames.isEmpty()) return;

        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();

        // ── 状态快照 → try/finally 全量还原 ──
        // 本 pass 改的全局状态比别人看到的多：混合开关+混合函数（预乘）、深度掩码、深度测试、剔除、
        // 0 号纹理单元（借它当场景深度纹理）、着色器颜色（归白）、当前着色器、模型视图矩阵（push/单位阵/乘关卡姿态）。
        // 任何一条路径漏还，后面所有渲染都会带着错状态跑（旧写法里"存着色器颜色数组引用"就等于没还 —— 见下）。
        RenderStateSnapshot snapshot = RenderStateSnapshot.capture();
        Matrix4f pose = event.getPoseStack().last().pose();
        PoseStack mvStack = RenderSystem.getModelViewStack();
        // push 放在 try 之外：finally 里的 pop 因此**一定**配对（若 push 在 try 内且它自己之前就抛异常，
        // finally 的 pop 会多弹一层 → 模型视图矩阵从此每帧漂一格，整屏错位且不会自愈）。
        mvStack.pushPose();
        try {
            // 着色器输出是预乘 alpha，必须显式设 (ONE, 1-SRC_ALPHA)：只调 enableBlend() 会沿用上一阶段
            // 残留的混合函数（通常是 SRC_ALPHA/1-SRC_ALPHA），等于把 alpha 再乘一遍 → 火焰偏暗发灰。
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.depthMask(false);    // 体积不写深度
            // 也<b>不测</b>硬件深度：包围盒面只是代理几何，它的深度没有物理意义 —— 一旦拿它去测，
            // "火与方块相交"就会变成"只剩边界、盒子内部透空"（方块后面的盒面被剔除，而那里本该有火）。
            // 遮挡改由片元着色器逐样本比对场景深度完成（Sampler0 = 主目标深度纹理）。
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();       // 6 面全发，不做面剔除

            // 把主渲染目标的深度纹理绑到纹理单元 0：draw 时 Sampler0 正是从这个单元取的。
            // 原值由快照保存并整份还回（不再手写 prevDepthTex：手写只覆盖"正常走到函数尾"这一条路径）。
            RenderSystem.setShaderTexture(0, net.minecraft.client.Minecraft.getInstance()
                    .getMainRenderTarget().getDepthTextureId());
            // ColorModulator 这个 uniform 在 draw 时会被 RenderSystem 的全局着色器颜色覆盖
            // （VertexBuffer._drawWithShader），前一阶段留下的染色（受击红闪之类）会把火焰染脏 → 先归白。
            // 注意：绝对不能用 float[] saved = RenderSystem.getShaderColor() 存"原值" ——
            // 它返回的是 RenderSystem 的内部数组，紧接着的 setShaderColor(1,1,1,1) 会把那份"原值"一起改写，
            // 事后还原等于强制刷白（历史 bug，已由快照的 float[4] 拷贝修掉）。
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            ShaderTimeUtil.setITime(shader);
            RenderSystem.setShader(() -> shader);

            // ── 显式设置模型视图矩阵（关键修复）──
            // 世界渲染各子系统各自 push/apply 模型视图栈，AFTER_PARTICLES 阶段栈上残留的可能是单位阵。
            // 若不显式设置，"相机相对世界坐标"会被当成<b>视图空间坐标</b>直接投影 →
            // 表现为火焰粘在镜头上（跟着摄像机走）、朝向错位（生成到玩家背后）。
            // 与 LockOutlineRenderer 同款：push → 单位阵 → 乘关卡姿态（纯视图旋转）→ apply，用完还原。
            mvStack.setIdentity();
            mvStack.mulPoseMatrix(pose);
            RenderSystem.applyModelViewMatrix();

            boolean firstPerson = net.minecraft.client.Minecraft.getInstance()
                    .options.getCameraType().isFirstPerson();
            Tesselator tess = Tesselator.getInstance();
            for (FlameEffects.Flame flame : flames) {
                float fade = FlameEffects.fade(flame);
                if (fade <= 0.001f) continue;

                prepareRopePoints(flame, partialTick);
                if (!bounds(flame, cam, partialTick)) continue;
                updateProjectedPx();

                setUniforms(shader, flame, cam, partialTick,
                        stepsFor(flames.size()), octavesFor(), centerCullFor(flame, firstPerson));
                if (!flame.renderLogged) {
                    flame.renderLogged = true;
                    LOGGER.info("[Flame] {} 首帧绘制: anchor={} cam=({}, {}, {}) box=[{}, {}, {}]~[{}, {}, {}] alpha={} poseBasis=({}, {}, {}, {})",
                            flame.shape, flame.anchor,
                            fmt(cam.x), fmt(cam.y), fmt(cam.z),
                            fmt(BOX[0]), fmt(BOX[1]), fmt(BOX[2]),
                            fmt(BOX[3]), fmt(BOX[4]), fmt(BOX[5]), fmt(fade * flame.alpha),
                            fmt(pose.m00()), fmt(pose.m02()), fmt(pose.m20()), fmt(pose.m22()));
                }

                // ── 单遍绘制：6 个面全发 ──
                // 为什么不用"深度预pass + 只发出口面"：预pass 必须 depthMask(true)，会把体积入口面写进
                // <b>主深度缓冲</b>（全局状态）→ 同帧里先画的效果在深度上挡住后面的效果，第二个效果连
                // 自己的入口面都过不了测试，表现为"只有特定角度/特定一个效果看得到"。深度预pass 在
                // 不污染主缓冲的前提下无法成立，故放弃。
                // 6 面全发 + 不写深度：任何相机位置（外部/内部/半进半出）覆盖都完整，且入口面片元
                // 先通过深度测试 → 只要"体积近侧"没被挡住就不会整团消失。代价是外部看会叠一次合成
                // （入口+出口各一次），已在 setUniforms 里用 VOLUME_COMPOSITE_COMPENSATION 把密度补回。
                RenderSystem.setShader(() -> shader);
                BufferBuilder bb = tess.getBuilder();
                bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                emitAllFaces(bb, flame.tintR, flame.tintG, flame.tintB, fade);
                BufferUploader.drawWithShader(bb.end());
            }
        } finally {
            // 模型视图栈先配对弹回（它不属于 GL 状态，快照管不到），再由快照整份还回其余全局状态。
            mvStack.popPose();
            RenderSystem.applyModelViewMatrix();
            snapshot.restore("flame-volume");
        }
    }

    // ────────────────────────── 锚定 ──────────────────────────

    /** 实体锚定时的"基座"位置（实体脚部插值位置）；世界锚定时返回 null。 */
    private static Vec3 followBase(FlameEffects.Flame f, float partialTick) {
        if (f.anchor != FlameEffects.Anchor.ENTITY || f.follower == null) return null;
        return f.follower.getPosition(partialTick);
    }

    /** 效果原点：世界锚定 = 固定 origin；实体锚定 = 实体位置 + 旋转后的局部偏移 + 垂直偏移。 */
    private static Vec3 anchorPos(FlameEffects.Flame f, float partialTick) {
        Vec3 base = followBase(f, partialTick);
        if (base == null) return f.origin;
        Vec3 local = rotateY(f.followOffset, anchorYaw(f));   // 局部偏移随实体转身一起转
        return base.add(local.x, f.followYOffset, local.z);
    }

    /** 锚定偏航（弧度）：实体锚定且 followYaw 时为"当前偏航 − 挂载时偏航"，否则 0。 */
    private static float anchorYaw(FlameEffects.Flame f) {
        if (f.anchor != FlameEffects.Anchor.ENTITY || f.follower == null || !f.followYaw) return 0.0f;
        return (float) Math.toRadians(f.follower.getYRot() - f.followSpawnYaw);
    }

    /** 火焰轴向（水平化 + 按锚定偏航旋转）。 */
    private static Vec3 axisOf(FlameEffects.Flame f) {
        Vec3 ax = new Vec3(f.axis.x, 0.0, f.axis.z);
        if (ax.lengthSqr() < 1.0E-6) ax = new Vec3(0.0, 0.0, 1.0);
        return rotateY(ax.normalize(), anchorYaw(f));
    }

    private static Vec3 rotateY(Vec3 v, float radians) {
        if (radians == 0.0f) return v;
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Vec3(v.x * c - v.z * s, v.y, v.x * s + v.z * c);
    }

    /**
     * 计算本帧火绳控制点的世界坐标写入 {@link #ROPE_WORLD}。
     * 实体锚定时：挂载瞬间的点集转成"实体相对"，再按当前实体位置与偏航增量还原
     * —— 于是火绳随实体平移与转身一起走，而不是留在原地。
     */
    private static void prepareRopePoints(FlameEffects.Flame f, float partialTick) {
        if (f.shape != FlameEffects.Shape.ROPE || f.points == null) return;
        Vec3 base = followBase(f, partialTick);
        float yaw = anchorYaw(f);
        for (int i = 0; i < 8; i++) {
            Vec3 p = f.points[i];
            if (base != null && f.followSpawnPos != null) {
                Vec3 rel = rotateY(p.subtract(f.followSpawnPos), yaw);
                ROPE_WORLD[i] = base.add(rel.x, rel.y, rel.z);
            } else {
                ROPE_WORLD[i] = p;
            }
        }
    }

    /**
     * 计算保守 AABB（相机相对）写入 {@link #BOX}。
     * 故意比真实火焰体大一圈：盒子只决定"从哪采到哪"，真实形状由着色器遮罩裁，
     * 宁大勿小 —— 小了会把火焰边缘切掉（出现平直的截面）。
     */
    private static boolean bounds(FlameEffects.Flame f, Vec3 cam, float partialTick) {
        Vec3 o = anchorPos(f, partialTick).subtract(cam);
        float pad = Math.max(f.thickness * 1.5f, 0.3f);

        switch (f.shape) {
            case WALL -> {
                Vec3 ax = axisOf(f);
                Vec3 n = new Vec3(0.0, 1.0, 0.0).cross(ax).normalize();
                double hx = ax.x * f.span * 0.5, hz = ax.z * f.span * 0.5;
                double nx = n.x * pad, nz = n.z * pad;
                out(o, (float) (Math.abs(hx) + Math.abs(nx)), (float) (Math.abs(hz) + Math.abs(nz)),
                        (float) (o.y - pad * 1.5), (float) (o.y + f.height + pad));
            }
            case RING -> {
                float r = f.span + pad;
                out(o, r, r, (float) (o.y - pad * 1.5), (float) (o.y + f.height + pad));
            }
            case ROPE -> {
                if (f.points == null) return false;
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                int count = 0;
                for (Vec3 p : ROPE_WORLD) {
                    if (p == null) break;
                    count++;
                    Vec3 rel = p.subtract(cam);
                    minX = Math.min(minX, (float) rel.x); maxX = Math.max(maxX, (float) rel.x);
                    minY = Math.min(minY, (float) rel.y); maxY = Math.max(maxY, (float) rel.y);
                    minZ = Math.min(minZ, (float) rel.z); maxZ = Math.max(maxZ, (float) rel.z);
                }
                if (count < 2) return false;
                BOX[0] = minX - pad; BOX[3] = maxX + pad;
                BOX[1] = minY - pad; BOX[4] = maxY + pad;
                BOX[2] = minZ - pad; BOX[5] = maxZ + pad;
            }
            default -> {   // BURNING：绕实体的包络
                float r = f.thickness + pad;
                out(o, r, r, (float) (o.y - f.thickness - pad), (float) (o.y + f.height + pad));
            }
        }
        return true;
    }

    /** 以 o 为中心、XZ 半径 rx/rz、y 区间 [y0,y1] 的盒子（相机相对）→ BOX。 */
    private static void out(Vec3 o, float rx, float rz, float y0, float y1) {
        BOX[0] = (float) o.x - rx; BOX[3] = (float) o.x + rx;
        BOX[2] = (float) o.z - rz; BOX[5] = (float) o.z + rz;
        BOX[1] = y0;               BOX[4] = y1;
    }

    // ────────────────────────── LOD（按屏幕覆盖率降质）──────────────────────────

    /**
     * 估计包围盒在屏幕上的投影尺寸（像素）：{@code 半径/距离 × 屏幕高度/(2·tan(fov/2))}。
     * 相机在该空间的原点，所以"距离"就是盒心的模长。
     */
    private static void updateProjectedPx() {
        double cx = (BOX[0] + BOX[3]) * 0.5;
        double cy = (BOX[1] + BOX[4]) * 0.5;
        double cz = (BOX[2] + BOX[5]) * 0.5;
        double radius = 0.5 * Math.sqrt(
                sq(BOX[3] - BOX[0]) + sq(BOX[4] - BOX[1]) + sq(BOX[5] - BOX[2]));
        double dist = Math.max(Math.sqrt(sq(cx) + sq(cy) + sq(cz)), 0.5);
        var mc = net.minecraft.client.Minecraft.getInstance();
        double halfH = mc.getWindow().getHeight() * 0.5;
        double halfFov = Math.toRadians(mc.options.fov().get() * 0.5);
        projectedPx = (float) (radius / dist * halfH / Math.tan(halfFov));
    }

    /**
     * 步数：火焰铺满屏幕时（第一人称贴着自己的着火最容易触发）每像素 28 步全跑满，
     * 实测帧率会从 119 掉到 12 —— 而大面积火焰本来就是糊的，降到 12~16 步肉眼几乎无差。
     */
    private static int stepsFor(int activeCount) {
        int steps = 28;
        if (projectedPx > 100.0f) steps -= (int) Math.min(16.0f, (projectedPx - 100.0f) / 25.0f);
        if (activeCount > 4) steps = Math.min(steps, 16);   // 多效果同屏 → 全局预算
        if (activeCount > 8) steps = Math.min(steps, 12);
        return Math.max(8, steps);
    }

    /** 湍流层数：越铺满屏幕越少层（5 → 3）。 */
    private static int octavesFor() {
        if (projectedPx > 260.0f) return 3;
        if (projectedPx > 90.0f) return 4;
        return 5;
    }

    /**
     * 第一人称"只画摄像机的四周边缘"—— <b>仅对"自己身上着火"生效</b>。
     *
     * <p>只有一种情况需要它：第一人称下自己被点燃（{@code BURNING} 补丁挂在摄像机实体上），
     * 火焰糊满整个屏幕 —— 此时每像素都要跑完整积分，而中心那一大块本来就是一整片火、
     * 既没有信息量又占了绝大多数像素。只保留四周一圈火舌，观感更像"身上着火"，
     * 帧率也能从 12 恢复到可用水平。</p>
     *
     * <p><b>其他效果一律不剔除</b>：火墙/火环/火绳、以及挂在别人身上的着火，
     * 无论在什么视角都要保持完整形态（那是玩家要看的东西）。</p>
     *
     * @return 中心剔除半径（屏幕归一化：0 = 正中、1 = 上下边缘中点）；0 = 不剔除
     */
    private static float centerCullFor(FlameEffects.Flame f, boolean firstPerson) {
        if (!firstPerson) return 0.0f;
        if (f.shape != FlameEffects.Shape.BURNING) return 0.0f;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (f.follower == null || f.follower != mc.getCameraEntity()) return 0.0f;
        return 0.60f;
    }

    private static double sq(double v) {
        return v * v;
    }

    // ────────────────────────── uniform ──────────────────────────

    private static void setUniforms(ShaderInstance sh, FlameEffects.Flame f, Vec3 cam, float partialTick,
                                    int steps, int octaves, float centerCull) {
        Vec3 anchor = anchorPos(f, partialTick);
        Vec3 o = anchor.subtract(cam);
        Vec3 ax = axisOf(f);
        Vec3 base = followBase(f, partialTick);

        // LOD：步数与湍流层数（按屏幕覆盖率自适应，见 stepsFor/octavesFor）
        sh.safeGetUniform("uSteps").set(steps);
        sh.safeGetUniform("uOctaves").set(octaves);
        // 第一人称中心剔除（只画四周边缘，见 centerCullFor）
        sh.safeGetUniform("uCenterCull").set(centerCull);

        // 几何是相机相对坐标 → 该空间里相机就在原点
        sh.safeGetUniform("uCameraWorld").set(0.0f, 0.0f, 0.0f);
        // 噪声必须加回相机世界位置才锚定在世界里（否则相机一动纹理就跟着游动）
        sh.safeGetUniform("uCamWorld").set((float) cam.x, (float) cam.y, (float) cam.z);
        // 两种连接方式：世界固定 → 锚点 0、偏航 0；追踪实体 → 锚点=实体位置、偏航=转角增量
        if (base != null) {
            sh.safeGetUniform("uNoiseAnchor").set((float) base.x, (float) base.y, (float) base.z);
        } else {
            sh.safeGetUniform("uNoiseAnchor").set(0.0f, 0.0f, 0.0f);
        }
        sh.safeGetUniform("uAnchorYaw").set(anchorYaw(f));

        sh.safeGetUniform("uBoxMin").set(BOX[0], BOX[1], BOX[2]);
        sh.safeGetUniform("uBoxMax").set(BOX[3], BOX[4], BOX[5]);
        sh.safeGetUniform("uOrigin").set((float) o.x, (float) o.y, (float) o.z);
        sh.safeGetUniform("uAxis").set((float) ax.x, (float) ax.y, (float) ax.z);

        sh.safeGetUniform("uShape").set(shapeIndex(f.shape));
        // z（密度）乘两项系数：多一次合成的补偿 + 整体透明度缩放（越小越透）
        sh.safeGetUniform("uParams").set(f.noiseScale, f.flow,
                f.density * VOLUME_COMPOSITE_COMPENSATION * OPACITY_SCALE, f.turbulence);
        // w = 不透明度上限；淡入淡出走顶点色 alpha（emitBackFaces 传 fade）
        sh.safeGetUniform("uParams2").set(f.thickness, f.height, f.span, f.alpha);
        // 火焰自身亮度（1.0 = 原样），通用提亮项：着火族从当前预设取值，其余效果默认 1.0。
        // 着色器侧另有 <=0 → 1.0 的防呆（见 FlameEffects.Flame#brightness）。
        sh.safeGetUniform("uBrightness").set(f.brightness);

        sh.safeGetUniform("uColorCore").set((float) f.colorCore.x, (float) f.colorCore.y, (float) f.colorCore.z);
        sh.safeGetUniform("uColorMid").set((float) f.colorMid.x, (float) f.colorMid.y, (float) f.colorMid.z);
        sh.safeGetUniform("uColorEdge").set((float) f.colorEdge.x, (float) f.colorEdge.y, (float) f.colorEdge.z);

        if (f.shape == FlameEffects.Shape.ROPE && f.points != null) {
            for (int i = 0; i < 8; i++) {
                Vec3 p = ROPE_WORLD[i] != null ? ROPE_WORLD[i] : f.points[i];
                Vec3 rel = p.subtract(cam);
                sh.safeGetUniform("uP" + i).set((float) rel.x, (float) rel.y, (float) rel.z);
            }
            sh.safeGetUniform("uPointCount").set(8.0f);
        } else {
            sh.safeGetUniform("uPointCount").set(0.0f);
        }
    }

    private static float shapeIndex(FlameEffects.Shape shape) {
        return switch (shape) {
            case WALL -> 0.0f;
            case RING -> 1.0f;
            case ROPE -> 2.0f;
            case BURNING -> 3.0f;
        };
    }

    // ────────────────────────── 几何 ──────────────────────────

    /**
     * 发射包围盒的<b>全部 6 个面</b>（相机在该空间的原点）。
     *
     * <p>为什么全发：凸盒的任意相机位置（外部 / 内部 / 半进半出）下，"每个像素至少被一个面覆盖"
     * 这一条恒成立，因此体积永远不会因面选择而缺角或整团消失。相机在盒内时 6 个面都是出口面、
     * 每像素恰好一个片元；在盒外时入口面 + 出口面各覆盖一次（多合成一次，密度已在
     * {@link #VOLUME_COMPOSITE_COMPENSATION} 里补偿）。</p>
     */
    private static void emitAllFaces(BufferBuilder bb, float r, float g, float b, float a) {
        float x0 = BOX[0], y0 = BOX[1], z0 = BOX[2];
        float x1 = BOX[3], y1 = BOX[4], z1 = BOX[5];

        quad(bb, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, r, g, b, a);  // +X
        quad(bb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);  // -X
        quad(bb, x0, y1, z1, x0, y1, z0, x1, y1, z0, x1, y1, z1, r, g, b, a);  // +Y
        quad(bb, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, r, g, b, a);  // -Y
        quad(bb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);  // +Z
        quad(bb, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r, g, b, a);  // -Z
    }

    private static void quad(BufferBuilder bb,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz,
                             float r, float g, float b, float a) {
        bb.vertex(ax, ay, az).color(r, g, b, a).endVertex();
        bb.vertex(bx, by, bz).color(r, g, b, a).endVertex();
        bb.vertex(cx, cy, cz).color(r, g, b, a).endVertex();
        bb.vertex(dx, dy, dz).color(r, g, b, a).endVertex();
    }
}
