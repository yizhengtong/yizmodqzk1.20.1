package net.minecraft.client.yiz.flame.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.yiz.api.ShaderProtectionRegistry;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

import static net.minecraft.client.renderer.RenderStateShard.*;

/**
 * 贴模型火焰着色器注册（{@code rendertype_flame_surface}）。
 *
 * <p>与 {@link FlameShaders} 同构（Iris 保护链 + 惰性解析 + 单个 RenderType），差别只有两点：</p>
 * <ul>
 *   <li><b>顶点格式 {@link DefaultVertexFormat#NEW_ENTITY}</b>：几何直接来自实体模型
 *       （实体渲染时由 {@link net.minecraft.client.yiz.modelmirror.ModelMirrorBufferSource} 双写一份），因为只有它带 Normal，
 *       而"沿表面法线向外舔"这件事必须有法线；</li>
 *   <li><b>不测也不写硬件深度（{@link RenderStateShard#NO_DEPTH_TEST}）</b>：火焰几何与本体完全重合，
 *       拿硬件深度测会出现大面积自遮挡闪烁；遮挡改由片元着色器逐样本比对场景深度
 *       （{@code Sampler0} = 主目标深度纹理），与体积火焰同一套思路。</li>
 * </ul>
 */
public final class FlameSurfaceShaders extends RenderType {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizFlame");

    private static final ResourceLocation SURFACE_ID =
            ResourceLocation.fromNamespaceAndPath("yizmodqzk", "rendertype_flame_surface");

    /** 顶点格式：实体模型的原生格式（Position/Color/UV0/UV1/UV2/Normal/Padding）。 */
    private static final VertexFormat FMT = DefaultVertexFormat.NEW_ENTITY;

    /** 预乘 alpha 混合（与体积火焰一致）：src 已含 alpha 权重 → ONE / 1-SRC_ALPHA。 */
    private static final TransparencyStateShard PREMULTIPLIED = new TransparencyStateShard(
            "flame_surface_premultiplied",
            () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(
                        GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                        GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            },
            () -> {
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            });

    /** 注册后的着色器实例（资源重载会替换）。 */
    public static ShaderInstance surface;
    /** 贴模型火焰 RenderType（着色器用 Supplier 注入，F3+T 重载后自动指向新实例）。 */
    public static RenderType surfaceType;

    private FlameSurfaceShaders(String name, VertexFormat format, VertexFormat.Mode mode,
                                int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                                Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    /**
     * 声明式注册（走 Iris 保护链，必须在 {@link ShaderProtectionRegistry#onRegisterShaders} 之前调用）。
     * 理由同 {@link FlameShaders#declareProtected()}：装了 Iris + 光影包时自定义着色器不会被静默屏蔽。
     */
    public static void declareProtected() {
        ShaderProtectionRegistry.registerShader(SURFACE_ID, FMT);
    }

    /** 取用着色器实例（惰性解析：{@code registerShader} 只是排队，事件期间取必然是 null）。 */
    public static ShaderInstance surface() {
        ShaderInstance instance = ShaderProtectionRegistry.getShader(SURFACE_ID);
        if (instance == null) return null;
        if (instance != surface) {
            surface = instance;
            if (surfaceType == null) {
                surfaceType = createSurfaceType(FlameSurfaceShaders::surface);
            }
            LOGGER.info("Flame surface shader resolved (Iris-protected)");
        }
        return instance;
    }

    private static RenderType createSurfaceType(Supplier<ShaderInstance> shader) {
        return create("flame_surface",
                FMT,
                VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(new ShaderStateShard(shader))
                        .setWriteMaskState(COLOR_WRITE)          // 只写颜色、不写深度（体积特效不参与遮挡）
                        .setCullState(NO_CULL)                   // 模型正反面都要烧：背面火焰由深度截断自然挡住
                        .setDepthTestState(NO_DEPTH_TEST)         // 几何与本体重合，硬件深度测会自遮挡闪烁
                        .setTransparencyState(PREMULTIPLIED)
                        .setOutputState(MAIN_TARGET)              // 保持当前目标（主渲染目标）
                        // 不设 TextureState：Sampler0 由渲染器手动绑成主目标深度纹理（深度截断用），
                        // 一旦设了贴图 shard，它会在 setupRenderState 里把 0 号纹理单元覆盖掉。
                        .createCompositeState(false));
    }
}
