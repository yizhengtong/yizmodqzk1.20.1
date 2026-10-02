package net.minecraft.client.yiz.flame.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.yiz.flame.FlameEffects;
import net.minecraft.client.yiz.lightning.util.ShaderTimeUtil;
import net.minecraft.client.yiz.modelmirror.MirrorChannel;
import net.minecraft.client.yiz.modelmirror.MirrorDiag;
import net.minecraft.client.yiz.modelmirror.MirrorFrame;
import net.minecraft.client.yiz.modelmirror.MirrorLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * 贴模型火焰作为"模型几何镜像"的一个消费者：几何来自 {@code ModelMirrorBufferSource} 双写的
 * 实体模型顶点，本类只负责<b>查询着火状态 + 绑 uniform + 刷帧</b>。
 *
 * <p>从旧的 {@code FlameSurfaceRenderer} 搬过来，行为逐字保持（逐实体 draw、LOD 降质、第一人称中心剔除、
 * 主目标深度纹理做逐样本截断）；差别只有"逐实体循环"改由通用驱动
 * {@code ModelMirrorRenderer} 做，本类只处理"一个实体的一层"。</p>
 */
public final class FlameSurfaceLayer implements MirrorLayer {

    public static final String ID = "flame";

    private static final Logger LOGGER = LoggerFactory.getLogger("YizFlame");

    /** 步进长度（格）：火焰从体表往外舔多远。0.42 ≈ 一次火舌的高度；再长就成火球了。 */
    private static final float MARCH_LENGTH = 0.42f;
    /** 起点抬离表面的偏移（格）：避免第一段采样和本体深度打架。 */
    private static final float SURFACE_OFFSET = 0.045f;
    /** 法线/视线混合：0 = 纯法线（火垂直表面外舔），1 = 纯视线（掠射角下更饱满）。 */
    private static final float NORMAL_VIEW_MIX = 0.45f;
    /**
     * 整体透明度上限（0.95 → 0.85）：用户要求"隔着火能看见身体本身的颜色/材质"，
     * 上限压下来给本体透出一部分；再低火就"虚"了。
     */
    private static final float OPACITY = 0.85f;
    /**
     * 噪声尺度 / 上升流速（跟旧的 fireShell 对齐：贴着身体的小火舌，不是一层均匀火壳）。
     */
    private static final float NOISE_SCALE = 4.4f, FLOW = 2.2f;
    /**
     * <b>斑驳程度</b>：湍流阈值（0.30 → 0.62）。湍流场归一化到 [0,1]，只有超过阈值的采样才算"火舌"；
     * 阈值越高空隙越大、火越"一簇簇"。用户反馈"下半身被均匀糊满"，主因就是它太低。
     * 调参方向：还想更斑驳 → 继续加到 0.7 左右；觉得火太稀 → 往 0.45 降。
     */
    private static final float TURBULENCE = 0.62f;
    /**
     * <b>整体浓度</b>：每步吸收率系数（15.0 → 5.0）。本 pass 的光程只有 {@link #MARCH_LENGTH} 格（约 0.4 格），
     * 15 会让几乎每一步都吸收到接近不透明（= 一层壳），降到 5.0 才有真正的空隙与层次。
     */
    private static final float DENSITY = 5.0f;
    /**
     * <b>边缘柔和度</b>：按"片元表面朝向相机的程度"（{@code |N·V|}）衰减。
     * 低于 {@link #EDGE_FADE_LO} 衰减到最弱、高于 {@link #EDGE_FADE_HI} 不衰减，
     * 中间 smoothstep → 火在部件轮廓处渐隐而不是硬切一刀。
     */
    private static final float EDGE_FADE_LO = 0.05f, EDGE_FADE_HI = 0.55f;
    /**
     * 轮廓处的最低强度（{@link #EDGE_FADE_LO} 处衰减到的值）。
     * <b>刻意不设 0</b>：轮廓处正是往外舔得最远的那批片元，压到 0 会把火舌一起削掉、
     * 只剩贴着表面的一层发光；0.35 = "轮廓处明显变透，但还有火舌挑出去"。
     */
    private static final float EDGE_FADE_MIN = 0.35f;
    /**
     * <b>向外衰减指数</b>：沿步进方向按 {@code exp(-k·进度)} 衰减（原来是一条直线 1-0.62·进度）。
     * 越大火舌越短越虚；指数曲线末段拖尾更长，不会有可见的"截断面"。
     */
    private static final float FALLOFF_K = 1.6f;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RenderType renderType() {
        return FlameSurfaceShaders.surfaceType;
    }

    @Override
    public MirrorFrame prepare(Entity entity, double dx, double dy, double dz, float yaw) {
        // 惰性解析：RegisterShadersEvent 期间 registerShader 只是排队，实例要等编译完才可取。
        // 未解析出来时本帧不抄几何（下一帧就好了）。
        if (FlameSurfaceShaders.surface() == null || FlameSurfaceShaders.surfaceType == null) return null;
        FlameEffects.SurfaceBurning state = FlameEffects.surfaceBurningOf(entity);
        if (state == null) return null;
        // alpha 只算一次并随帧参数带下去：顶点写入与刷帧用的是同一个值
        return new MirrorFrame(entity, dx, dy, dz, yaw, state.alpha());
    }

    @Override
    public void draw(MirrorChannel channel) {
        MirrorFrame frame = channel.frame();
        ShaderInstance shader = FlameSurfaceShaders.surface();
        FlameEffects.SurfaceBurning state = FlameEffects.surfaceBurningOf(frame.entity());
        if (shader == null || state == null) {
            // 理论不可达（prepare 已经查过）：真发生就丢掉这批顶点，别用过期 uniform 画出去
            channel.discard();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        // LOD 判据只算一次（屏幕投影尺寸），uniform 与日志共用
        float px = projectedPx(frame.entity(), cam);
        setUniforms(shader, frame, state, cam, firstPerson, px);

        int flushed = channel.flush();
        if (MirrorDiag.first("flame:draw:" + MirrorDiag.entity(frame.entity()))) {
            LOGGER.info("[FlameSurface] 绘制: entity={} 顶点={} spread={} steps={} boxH={}",
                    MirrorDiag.entity(frame.entity()),
                    flushed, fmt(state.spread()), stepsFor(px),
                    fmt(frame.entity().getBbHeight()));
        }
    }

    /** 本层的片元着色器逐样本比对主目标深度纹理做遮挡/截断 → 需要驱动先把深度纹理绑到 Sampler0。 */
    @Override
    public boolean needsSceneDepth() {
        return true;
    }

    // ────────────────────────── uniform ──────────────────────────

    private static void setUniforms(ShaderInstance sh, MirrorFrame frame,
                                    FlameEffects.SurfaceBurning state, Vec3 cam, boolean firstPerson, float px) {
        // LOD：按实体屏幕投影尺寸降质（和体积火焰同一套判据，见 FlameRenderer#stepsFor/octavesFor）
        sh.safeGetUniform("uSteps").set(stepsFor(px));
        sh.safeGetUniform("uOctaves").set(px > 260.0f ? 3 : (px > 90.0f ? 4 : 5));
        // 第一人称"自己着火"只画四周边缘（本 pass 在第一人称通常没有几何 —— 原版不渲染本地玩家模型，
        // 见 FlameEffects.SurfaceBurning 的第一人称兜底；这里是给"显示本地模型"的 MOD 留的保护）
        sh.safeGetUniform("uCenterCull").set(centerCullFor(frame.entity(), firstPerson));

        // 顶点是视图空间坐标：世界坐标 = 相机世界坐标 + IViewRotMat * 视图坐标（由 shader 反旋转）
        sh.safeGetUniform("uCamWorld").set((float) cam.x, (float) cam.y, (float) cam.z);
        // 噪声锚点 = 实体脚部世界坐标 = 相机世界坐标 + 实体渲染时的相机相对位置
        sh.safeGetUniform("uNoiseAnchor").set(
                (float) (cam.x + frame.dx()), (float) (cam.y + frame.dy()), (float) (cam.z + frame.dz()));
        // 身体朝向（弧度）：采样点反旋转到身体坐标系 → 实体转身时火焰图案不跟着"游动"
        sh.safeGetUniform("uAnchorYaw").set((float) Math.toRadians(frame.yaw()));

        sh.safeGetUniform("uSpread").set(state.spread());
        sh.safeGetUniform("uSpreadHeight").set((float) Math.max(frame.entity().getBbHeight(), 0.05f));

        sh.safeGetUniform("uParams").set(NOISE_SCALE, FLOW, DENSITY, TURBULENCE);
        sh.safeGetUniform("uParams2").set(MARCH_LENGTH, SURFACE_OFFSET, NORMAL_VIEW_MIX, OPACITY);
        // x/y = 边缘柔和度区间（|N·V|），z = 向外衰减指数，w = 轮廓处最低强度
        sh.safeGetUniform("uParams3").set(EDGE_FADE_LO, EDGE_FADE_HI, FALLOFF_K, EDGE_FADE_MIN);

        sh.safeGetUniform("uColorCore").set((float) FlameEffects.CORE.x, (float) FlameEffects.CORE.y, (float) FlameEffects.CORE.z);
        sh.safeGetUniform("uColorMid").set((float) FlameEffects.MID.x, (float) FlameEffects.MID.y, (float) FlameEffects.MID.z);
        sh.safeGetUniform("uColorEdge").set((float) FlameEffects.EDGE.x, (float) FlameEffects.EDGE.y, (float) FlameEffects.EDGE.z);

        // iTime 由绘制路径喂入（JSON 里声明了；VertexBuffer#_drawWithShader 不会覆盖它）
        ShaderTimeUtil.setITime(sh);
    }

    /**
     * 步数：实体占屏越大越省（贴脸烧的僵尸能占满屏幕，16 步 × 5 层 3D simplex 是纯浪费；
     * 而大面积火焰本来就是糊的，降到 10 步肉眼几乎无差）。
     */
    private static int stepsFor(float px) {
        if (px > 260.0f) return 10;
        if (px > 90.0f) return 12;
        return 16;
    }

    /** 实体在屏幕上的投影尺寸（像素）：半径/距离 × 屏幕高/(2·tan(fov/2))，与 FlameRenderer 同式。 */
    private static float projectedPx(Entity entity, Vec3 cam) {
        double radius = Math.max(entity.getBbWidth(), entity.getBbHeight()) * 0.5 + 0.2;
        double dist = Math.max(entity.position().distanceTo(cam), 0.5);
        Minecraft mc = Minecraft.getInstance();
        double halfH = mc.getWindow().getHeight() * 0.5;
        double halfFov = Math.toRadians(mc.options.fov().get() * 0.5);
        return (float) (radius / dist * halfH / Math.tan(halfFov));
    }

    /** 第一人称 + 目标就是摄像机实体 → 只画四周边缘；其余情况一律完整绘制。 */
    private static float centerCullFor(Entity entity, boolean firstPerson) {
        if (!firstPerson) return 0.0f;
        return entity == Minecraft.getInstance().getCameraEntity() ? 0.60f : 0.0f;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
