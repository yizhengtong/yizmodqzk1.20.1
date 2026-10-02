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
 * 体表闪电作为"模型几何镜像"的一个消费者（{@code rendertype_lightning_surface}）。
 *
 * <p><b>"挂在实体身上的弧"走哪条路（用户两次反馈后的定案）</b>：实体身上的一切弧都由模型几何镜像负责 ——
 * 几何就是实体模型本身，所以天然贴合四肢/头部/盔甲的轮廓。本层负责<b>持续感电</b>那一种
 * （由 {@code ShockedEntityAPI} 驱动、寿命 = 整个感电时长、沿 UV 缓慢游走）；
 * <b>命中瞬间</b>那种短寿命游离弧由同一份几何的另一个消费者
 * {@link LightningAirArcLayer}（{@code AIR_ARC} 层，{@code rendertype_lightning_modelarc}）负责。</p>
 *
 * <p>而老通道 {@code SurfaceArcEffect}（{@code LightningRenderer.SURFACE}）是按<b>实体包围盒六个面</b>
 * 生成小电弧的，几何与模型无关（会飘在头顶上方、脚下方那些包围盒面之外），
 * 现在<b>只</b>保留"球状闪电表面"那一种用法（球不是实体，没有模型可镜像）——
 * 实体身上的弧一律不再经过它（见 {@code S2CShockFxPayload} 的 kind=0 分支注释）。</p>
 *
 * <p><b>与旧实现（{@code LivingEntityRendererSurfaceLightningMixin}）的关系</b>：旧路在
 * {@code LivingEntityRenderer.render} 的 popPose 之前把 {@code getModel()} <b>整个再渲染一遍</b> ——
 * 骨骼/动画/GeckoLib 全部重算一次，而且只覆盖 {@code getModel()} 这一层（盔甲/手持物不参与）。
 * 现在几何来自实体渲染时的双写（顶点属性一字不改：UV0 路径、法线、UV1 overlay、UV2 光照全保留），
 * 只求值一次，且天然包含所有实际画出来的本体部位。</p>
 *
 * <p><b>为什么刷帧能落在和本体一模一样的位置</b>：双写的顶点就是本体那一批（同样的 PoseStack、
 * 同样的矩阵），刷帧又在 AFTER_ENTITIES（此时 ModelViewMat 与实体渲染时相同，见
 * {@code ModelMirrorRenderer} 的类注释），所以两者逐字节重合；
 * {@code rendertype_lightning_surface} 的深度测试是 {@code <=}（515 = GL_LEQUAL）→ 重合处照样通过，
 * 闪电就画在本体表面上（旧路靠"切 RenderType 时把本体批次提前刷掉"达到同样的先后关系）。</p>
 *
 * <p><b>顶点着色器要的量都在</b>：{@code Position}（骨骼变换后、相对实体）、{@code Normal}（视图空间）、
 * {@code UV0}（fsh 的闪电路径就是沿着它走的）。旧实现另外传的 {@code packedLight} 与
 * {@code OverlayTexture.NO_OVERLAY} 也<b>不需要丢</b> —— 双写保留的是模型自己写出的 UV2（光照）与
 * UV1（overlay），而本 shader 两个都没读（fsh 只读 vUV/iTime/ColorModulator），观感无差别。</p>
 */
public final class LightningSurfaceLayer implements MirrorLayer {

    public static final String ID = "lightning";

    private static final Logger LOGGER = LoggerFactory.getLogger("YizLightning");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RenderType renderType() {
        return LightningShaders.surfaceType;
    }

    @Override
    public MirrorFrame prepare(Entity entity, double dx, double dy, double dz, float yaw) {
        // 着色器未解析（首帧 / F3+T 重载中）→ 本帧不抄几何
        if (LightningShaders.surface == null || LightningShaders.surfaceType == null) return null;
        float fade = LightningSurfaceState.fadeOf(entity);
        if (fade <= 0.0f) return null;
        return new MirrorFrame(entity, dx, dy, dz, yaw, fade);
    }

    @Override
    public void draw(MirrorChannel channel) {
        ShaderInstance shader = LightningShaders.surface;
        if (shader == null || LightningShaders.surfaceType == null) {
            channel.discard();
            return;
        }
        MirrorFrame frame = channel.frame();
        float fade = frame.alpha();
        ShaderTimeUtil.setITime(shader);

        // ── 强度入口：ColorModulator ──
        // fsh 是 fragColor = vec4(col, intensity) * ColorModulator，顶点色它一个都没读，
        // 所以"渐显/渐隐/被动带电的低亮度"只能从这里进。
        // 不能写 shader.getUniform("ColorModulator").set(...)：VertexBuffer#_drawWithShader 在 shader.apply()
        // 之前会用 RenderSystem.getShaderColor() 覆盖它 —— 旧 mixin 那一行因此是死代码（渐显渐隐从未生效）。
        // 唯一有效的通道就是 RenderSystem 的着色器颜色，且必须在 draw 之后立刻还原（它是全局状态）。
        //
        // ⚠️ 这里**必须**逐值拷进局部变量再还原：RenderSystem.getShaderColor() 返回的是它内部那个
        // float[4] 数组本身（RenderSystem.java:512-515），"存引用再还原"会被紧接着的
        // setShaderColor(fade,…) 一起改写 → 还原等于把 fade 又写回去一遍，全局染色从此停在暗值
        // （体积火焰 FlameRenderer 踩过同一个坑：它那行还原实际等于强制刷白）。
        //
        // 为什么这一层不整份快照：本层只动着色器颜色，而快照要读 10 个 GL 枚举（glGetIntegerv 可能拖管线），
        // 本层是"每实体每帧一次"的热路径；整份快照交给每帧一次的 ModelMirrorRenderer 做（见那儿的 finally）。
        float[] prev = RenderSystem.getShaderColor();          // 注意：返回的是内部数组，不能直接留着引用
        float pr = prev[0], pg = prev[1], pb = prev[2], pa = prev[3];
        RenderSystem.setShaderColor(fade, fade, fade, fade);
        int flushed;
        try {
            flushed = channel.flush();
        } finally {
            RenderSystem.setShaderColor(pr, pg, pb, pa);
        }

        if (MirrorDiag.first("lightning:draw:" + MirrorDiag.entity(frame.entity()))) {
            LOGGER.info("[LightningSurface] 绘制: entity={} 顶点={} fade={}（几何来自实体模型双写，贴的是模型本身）",
                    MirrorDiag.entity(frame.entity()), flushed, fade);
        }
    }

    /**
     * 本层不需要场景深度纹理：{@code rendertype_lightning_surface} 用的是<b>硬件</b>深度测试
     * （{@code <=}，且只写颜色不写深度），遮挡由深度缓冲自然完成，片元着色器里没有 Sampler0。
     */
    @Override
    public boolean needsSceneDepth() {
        return false;
    }
}
