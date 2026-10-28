package net.minecraft.client.yiz.flame.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.yiz.flame.FlameEffects;
import net.minecraft.client.yiz.flame.FlameStylePreset;
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

    // ────────────────────────── 本 pass 的固定几何参数 ──────────────────────────
    // 光程（marchLength）不在这里写死：它属于"可观感旋钮"，逐帧从当前预设取
    // （{@code FlameStylePreset#marchLength}，默认档 0.42 ≈ 一次火舌的高度，再长就成火球了）。
    /** 起点抬离表面的偏移（格）：避免第一段采样和本体深度打架。<b>不是风格旋钮</b>，固定值。 */
    private static final float SURFACE_OFFSET = 0.045f;
    /** 法线/视线混合：0 = 纯法线（火垂直表面外舔），1 = 纯视线（掠射角下更饱满）。<b>固定值</b>。 */
    private static final float NORMAL_VIEW_MIX = 0.45f;

    // ────────────────────────── 观感参数（两条路径共用同一来源）──────────────────────────
    // 数值不在这里写死：默认档看 FlameStylePreset.CLASSIC（两条路径共用一份常量 → "自己身上"与"别的实体身上"
    // 不可能调出两种浓淡），运行时要换风格看 FlameStylePreset（/yiz flame preset <名字>）。
    /**
     * <b>边缘柔和度</b>：按"片元表面朝向相机的程度"（{@code |N·V|}）衰减。
     * 低于 {@link #EDGE_FADE_LO} 衰减到最弱、高于 {@link #EDGE_FADE_HI} 不衰减，
     * 中间 smoothstep → 火在部件轮廓处渐隐而不是硬切一刀。<b>不加进预设</b>：它是"外边界怎么收"
     * 的实现细节，不是用户看得懂的风格选项（预设只暴露 falloffK 与 edgeFadeMin 两个）。
     */
    private static final float EDGE_FADE_LO = 0.05f, EDGE_FADE_HI = 0.55f;

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
        // 诊断（每个实体只打一条）：把"这一帧真正喂给着色器的参数"打出来。
        // 用途一：对账"玩家与生物是不是同一套参数"——uSpreadHeight 会随目标身高变化，其余必须逐值相同。
        // 用途二：火线高度不到预期时，直接看这里的 身高 与 spread 对不对。
        if (MirrorDiag.first("flame:draw:" + MirrorDiag.entity(frame.entity()))) {
            FlameStylePreset p = FlameStylePreset.active();
            LOGGER.info("[FlameSurface] 绘制: entity={} 顶点={} 身高={} spread={} 亮度={} 透明度上限={} 密度={} steps={} 预设={}",
                    MirrorDiag.entity(frame.entity()),
                    flushed, fmt(frame.entity().getBbHeight()), fmt(state.spread()),
                    fmt(p.brightness), fmt(p.opacity), fmt(p.density), stepsFor(px), p.id);
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
        // 当前预设：所有"可观感旋钮"都从它读（默认档 = FlameStylePreset.CLASSIC）。
        // 逐帧读而不是缓存：/yiz flame preset 切完当帧就能看见变化。
        FlameStylePreset style = FlameStylePreset.active();
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
        // 高度归一化基准 = <b>目标自己的身高</b>（不写死 1.8）：2.9 格的监守者与 1.8 格的玩家
        // 共用同一条"火线停在身高某个比例"的规则（着色器里 SPREAD_LINE_MAX + 噪声 + SPREAD_SOFT）。
        sh.safeGetUniform("uSpreadHeight").set((float) Math.max(frame.entity().getBbHeight(), 0.05f));
        // 火焰自身亮度：与第一人称体积兜底、体积补丁同一个来源（当前预设），
        // 且着色器里 <=0 会退回 1.0 防呆（见 rendertype_flame_surface.fsh 末尾）
        sh.safeGetUniform("uBrightness").set(state.brightness());

        // x=噪声尺度 y=上升流速 z=密度/吸收率 w=湍流阈值（越高越斑驳）
        sh.safeGetUniform("uParams").set(style.noiseScale, style.flow, style.density, style.turbulence);
        // x=光程(格) y=表面外偏移 z=法线/视线混合 w=透明度上限
        sh.safeGetUniform("uParams2").set(style.marchLength, SURFACE_OFFSET, NORMAL_VIEW_MIX, style.opacity);
        // x/y = 边缘柔和度区间（|N·V|），z = 向外衰减指数，w = 轮廓处最低强度
        sh.safeGetUniform("uParams3").set(EDGE_FADE_LO, EDGE_FADE_HI, style.falloffK, style.edgeFadeMin);

        sh.safeGetUniform("uColorCore").set((float) style.core.x, (float) style.core.y, (float) style.core.z);
        sh.safeGetUniform("uColorMid").set((float) style.mid.x, (float) style.mid.y, (float) style.mid.z);
        sh.safeGetUniform("uColorEdge").set((float) style.edge.x, (float) style.edge.y, (float) style.edge.z);

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
