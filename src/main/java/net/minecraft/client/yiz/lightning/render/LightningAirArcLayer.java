package net.minecraft.client.yiz.lightning.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.yiz.lightning.util.ShaderTimeUtil;
import net.minecraft.client.yiz.modelmirror.MirrorChannel;
import net.minecraft.client.yiz.modelmirror.MirrorDiag;
import net.minecraft.client.yiz.modelmirror.MirrorFrame;
import net.minecraft.client.yiz.modelmirror.MirrorLayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「围绕实体的空气电弧」作为模型几何镜像的一个消费者（{@code rendertype_lightning_modelarc}）。
 *
 * <p><b>它取代了什么</b>：{@code SurfaceArcEffect}（{@code LightningRenderer.SURFACE}）那套
 * "按实体包围盒六个面撒小弧"的表现。用户明确要求"空气电弧也应该跟着模型轮廓"，而包围盒弧无论怎么调参数
 * 都会飘在模型之外（头顶上方/脚下方那些盒面），所以"挂在实体身上"的游离弧统一改走本层：
 * 几何就是实体模型自己的顶点（与 {@code LightningSurfaceLayer} 同源，同一帧双写下来的那一份），
 * 逐点贴合四肢/头部/盔甲轮廓 —— 不会再出现"盒子形状的弧"。</p>
 *
 * <p><b>与体表层（{@link LightningSurfaceLayer}）的分工</b>：</p>
 * <ul>
 *   <li>{@code lightning}（体表持续电流）：由感电状态 {@code ShockedEntityAPI} 驱动，
 *       寿命长（整个感电时长）、沿 UV 缓慢游走、稀疏生死门控；</li>
 *   <li>{@code airarc}（本层，命中/技能游离弧）：由 {@link LightningAirArcState} 驱动，
 *       寿命短（默认 0.35s，与命中瞬间一致）、更快更亮、弧更细更碎，
 *       用同一份几何、另一个着色器画出来 —— 视觉上"炸开的电弧"而不是"一层电壳"。</li>
 * </ul>
 *
 * <p><b>顶点着色器要的量都在</b>：{@code Position}（骨骼变换后、相对实体）、{@code Normal}（视图空间）、
 * {@code UV0}（fsh 的闪电路径沿它走）、{@code Color}（alpha = 本帧强度）。</p>
 */
public final class LightningAirArcLayer implements MirrorLayer {

    public static final String ID = "airarc";

    private static final Logger LOGGER = LoggerFactory.getLogger("YizLightning");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RenderType renderType() {
        return LightningShaders.modelArcType;
    }

    @Override
    public MirrorFrame prepare(Entity entity, double dx, double dy, double dz, float yaw) {
        // 着色器未解析（首帧 / F3+T 重载中）→ 本帧不抄几何
        if (LightningShaders.modelArc == null || LightningShaders.modelArcType == null) return null;
        if (entity == null || entity.isInvisible()) return null;   // 隐形单位不暴露位置（与体表层同策）
        float fade = LightningAirArcState.fadeOf(entity.getId());
        if (fade <= 0.0f) return null;
        return new MirrorFrame(entity, dx, dy, dz, yaw, fade);
    }

    @Override
    public void draw(MirrorChannel channel) {
        ShaderInstance shader = LightningShaders.modelArc;
        MirrorFrame frame = channel.frame();
        if (shader == null || LightningShaders.modelArcType == null || frame == null) {
            channel.discard();
            return;
        }
        float fade = frame.alpha();
        ShaderTimeUtil.setITime(shader);

        float[] col = LightningAirArcState.colorOf(frame.entity().getId());
        shader.safeGetUniform("uArcColor").set(col[0], col[1], col[2]);
        shader.safeGetUniform("uArcWidth").set(LightningAirArcState.halfWidthOf(frame.entity().getId()));
        // 每实体错开相位：同一帧里多个目标身上的弧不会整齐划一地闪
        shader.safeGetUniform("uArcSeed").set(frame.entity().getId() % 64 * 1.37f);

        // ── 强度入口：ColorModulator（与体表层同一套理由，见 LightningSurfaceLayer#draw 的注释）──
        // 必须逐值拷进局部变量再还原：RenderSystem.getShaderColor() 返回的是内部数组本身。
        float[] prev = RenderSystem.getShaderColor();
        float pr = prev[0], pg = prev[1], pb = prev[2], pa = prev[3];
        RenderSystem.setShaderColor(fade, fade, fade, fade);
        int flushed;
        try {
            flushed = channel.flush();
        } finally {
            RenderSystem.setShaderColor(pr, pg, pb, pa);
        }

        if (MirrorDiag.first("airarc:draw:" + MirrorDiag.entity(frame.entity()))) {
            LOGGER.info("[LightningAirArc] 绘制: entity={} 顶点={} fade={}（几何来自实体模型双写 —— "
                            + "围绕实体的游离弧贴的是模型轮廓，不再是包围盒）",
                    MirrorDiag.entity(frame.entity()), flushed, fade);
        }
    }

    /** 与体表层一致：着色器里没有 Sampler0，遮挡交给硬件深度测试（{@code <=}）。 */
    @Override
    public boolean needsSceneDepth() {
        return false;
    }
}
