package net.minecraft.client.yiz.lightning.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 闪电特效着色器注册 — 走 {@link RegisterShadersEvent}。
 * arc = 线段电弧（ribbon），ball = 球状闪电（billboard），spark = 火花粒子（拖尾 billboard），
 * surface = 体表闪电（贴附实体模型表面，NEW_ENTITY 格式含 Normal）。
 */
public final class LightningShaders {

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
    /** 体表闪电表面 RenderType（NEW_ENTITY 格式 + 加法混合 + EQUAL 深度贴附模型表面）。 */
    public static net.minecraft.client.renderer.RenderType surfaceType;

    private LightningShaders() {}

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
                           surfaceType = net.minecraft.client.yiz.api.ShaderManager.createLightningSurfaceType(() -> surface);
                           LOGGER.info("Lightning surface shader loaded"); });
        } catch (IOException e) {
            LOGGER.error("Failed to load lightning shaders", e);
        }
    }
}
