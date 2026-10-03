package net.minecraft.client.yiz.lightning.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 闪电着色器注册 —— 走 {@link RegisterShadersEvent}（<b>MOD 总线</b>，见 ShaderEventRegistrar）。
 *
 * <p><b>现在只剩一个：{@code arc}</b>（{@code rendertype_lightning_arc}）—— 线段电弧 ribbon，
 * 编号 1「闪电链」与编号 4「围弧」<b>共用</b>它（围弧只是端点算在模型局部空间，渲染器同一个）。</p>
 *
 * <p><b>已删除的着色器</b>（用户实测后确认无用）：{@code ball}（球状闪电）、{@code spark}（火花粒子）、
 * {@code surface}（旧体表叠亮，原编号 5 的 mixin 用它）、{@code modelarc}（贴模型体表电流，原编号 3）。
 * 后两个连同 {@code rendertype_lightning_surface.*} / {@code rendertype_lightning_modelarc.*}
 * <b>资源文件也已删除，不留档</b> —— 它们服务的那两条路曾经一开就整屏发黑，
 * 根因（着色器 JSON 缺 {@code "blend"} 节点 ⇒ {@code opaque=true} ⇒ 着色器自己
 * {@code RenderSystem.disableBlend()}）写在 {@link LightningRenderer} 的类注释里。</p>
 *
 * <p>继承 {@link RenderType} 只为拿到 protected 的 {@code create(...)} 与 RenderStateShard 常量
 * （1.20.1 没有 AT 公开它们），本类自身不参与渲染。</p>
 */
public final class LightningShaders extends RenderType {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizLightning");

    private static final ResourceLocation ARC_ID =
            ResourceLocation.fromNamespaceAndPath("yizmodqzk", "rendertype_lightning_arc");

    private static final VertexFormat FMT = DefaultVertexFormat.POSITION_TEX_COLOR;

    /** 唯一的闪电着色器：电弧 ribbon（编号 1 与 4 共用）。 */
    public static ShaderInstance arc;

    private LightningShaders(String name, VertexFormat format, VertexFormat.Mode mode,
                             int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                             Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), ARC_ID, FMT),
                    s -> { arc = s; LOGGER.info("Lightning arc shader loaded"); });
        } catch (IOException e) {
            LOGGER.error("Failed to load lightning shaders", e);
        }
    }
}
