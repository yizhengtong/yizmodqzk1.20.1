package net.minecraft.client.yiz.lightning.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.function.Supplier;

import static net.minecraft.client.renderer.RenderStateShard.*;

/**
 * 闪电特效着色器注册 —— 走 {@link RegisterShadersEvent}（<b>MOD 总线</b>，见 ShaderEventRegistrar）。
 *
 * <p>arc = 线段电弧（ribbon），ball = 球状闪电（billboard），spark = 火花粒子（拖尾 billboard），
 * surface = 体表闪电（贴附实体模型表面，NEW_ENTITY 格式含 Normal）。</p>
 *
 * <p>继承 {@link RenderType} 只为拿到 protected 的 {@code create(...)} 与 RenderStateShard 常量
 * （1.20.1 没有 AT 公开它们），本类自身不参与渲染。</p>
 */
public final class LightningShaders extends RenderType {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizLightning");

    private static final ResourceLocation ARC_ID =
            ResourceLocation.fromNamespaceAndPath("yizmodqzk", "rendertype_lightning_arc");
    private static final ResourceLocation BALL_ID =
            ResourceLocation.fromNamespaceAndPath("yizmodqzk", "rendertype_lightning_ball");
    private static final ResourceLocation SPARK_ID =
            ResourceLocation.fromNamespaceAndPath("yizmodqzk", "rendertype_lightning_spark");
    private static final ResourceLocation SURFACE_ID =
            ResourceLocation.fromNamespaceAndPath("yizmodqzk", "rendertype_lightning_surface");

    private static final VertexFormat FMT = DefaultVertexFormat.POSITION_TEX_COLOR;

    public static ShaderInstance arc;
    public static ShaderInstance ball;
    public static ShaderInstance spark;
    public static ShaderInstance surface;
    /** 体表闪电表面 RenderType（NEW_ENTITY 格式 + 加法混合 + LEQUAL 深度贴附模型表面）。 */
    public static RenderType surfaceType;

    private LightningShaders(String name, VertexFormat format, VertexFormat.Mode mode,
                             int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                             Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), ARC_ID, FMT),
                    s -> { arc = s; LOGGER.info("Lightning arc shader loaded"); });
            event.registerShader(new ShaderInstance(event.getResourceProvider(), BALL_ID, FMT),
                    s -> { ball = s; LOGGER.info("Lightning ball shader loaded"); });
            event.registerShader(new ShaderInstance(event.getResourceProvider(), SPARK_ID, FMT),
                    s -> { spark = s; LOGGER.info("Lightning spark shader loaded"); });
            event.registerShader(new ShaderInstance(event.getResourceProvider(), SURFACE_ID, DefaultVertexFormat.NEW_ENTITY),
                    s -> { surface = s;
                           surfaceType = createSurfaceType(() -> surface);
                           LOGGER.info("Lightning surface shader loaded"); });
        } catch (IOException e) {
            LOGGER.error("Failed to load lightning shaders", e);
        }
    }

    /**
     * 体表闪电表面 RenderType：NEW_ENTITY 格式（含 Normal）+ 加法混合 + {@code <=} 深度，
     * 让闪电贴附在实体模型表面。
     *
     * <p>着色器用 Supplier 注入，因此资源重载（F3+T）后自动指向新的 {@link ShaderInstance}，
     * 不需要重建 RenderType。</p>
     */
    private static RenderType createSurfaceType(Supplier<ShaderInstance> shader) {
        ShaderStateShard shaderState = new ShaderStateShard(shader);
        TransparencyStateShard additive = new TransparencyStateShard("lightning_additive",
                () -> {
                    com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                    com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
                            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE,
                            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
                },
                () -> {
                    com.mojang.blaze3d.systems.RenderSystem.disableBlend();
                    com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
                });
        return create("lightning_surface",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(shaderState)
                        .setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL)
                        .setDepthTestState(new DepthTestStateShard("<=", 515)).setTransparencyState(additive)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .createCompositeState(false));
    }
}
