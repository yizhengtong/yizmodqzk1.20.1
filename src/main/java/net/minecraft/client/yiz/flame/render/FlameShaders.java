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
 * 体积火焰着色器注册（{@link RegisterShadersEvent}，<b>MOD 总线</b>，见 ShaderEventRegistrar）。
 *
 * <p>与 {@code LightningShaders} 同构：继承 {@link RenderType} 只为拿到 protected 的
 * {@code create(...)} 与 RenderStateShard 常量（1.20.1 没有 AT 公开它们），本类自身不参与渲染。</p>
 *
 * <p><b>混合方式</b>：着色器输出<b>预乘 alpha</b>（{@code vec4(acc, 1-transmittance)}，acc 已乘过吸收率），
 * 所以这里不能用原版 {@code TRANSLUCENT_TRANSPARENCY}（那是非预乘 SRC_ALPHA/1-SRC_ALPHA），
 * 自定义成 {@code (ONE, ONE_MINUS_SRC_ALPHA)} —— 物理上正确的"over"合成，火焰叠加时不会发灰。</p>
 *
 * <p><b>深度</b>：只写颜色不写深度（体积特效不参与遮挡），深度测试 LEQUAL 保留 —— 地形/实体仍能挡住火焰。</p>
 */
public final class FlameShaders extends RenderType {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizFlame");

    private static final ResourceLocation FLAME_ID =
            ResourceLocation.fromNamespaceAndPath("yizmodqzk", "rendertype_flame_volume");

    /** 顶点格式：只要位置 + 颜色（颜色用作整体染色与 alpha 淡入淡出）。 */
    private static final VertexFormat FMT = DefaultVertexFormat.POSITION_COLOR;

    /** 注册后的着色器实例（资源重载会替换）。 */
    public static ShaderInstance flame;
    /** 体积火焰 RenderType（着色器用 Supplier 注入，F3+T 重载后自动指向新实例）。 */
    public static RenderType flameType;

    private FlameShaders(String name, VertexFormat format, VertexFormat.Mode mode,
                         int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                         Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    /**
     * 声明式注册（走 Iris 保护链）。
     *
     * <p><b>必须在 {@link ShaderProtectionRegistry#onRegisterShaders} 之前调用</b>：
     * 它只往 ENTRIES 里登记，真正的 {@code event.registerShader} 由保护注册表统一执行，
     * 并在注册前调用 {@code ShaderEnvironmentAPI.ensureShaderCompatibility()} ——
     * 装了 Iris + 光影包且 {@code allowUnknownShaders=false} 时，自定义着色器不会被静默屏蔽。</p>
     */
    public static void declareProtected() {
        ShaderProtectionRegistry.registerShader(FLAME_ID, FMT);
    }

    /**
     * 取用着色器实例（<b>惰性解析</b>）。
     *
     * <p>为什么不能在 {@code RegisterShadersEvent} 里直接取：{@code event.registerShader(...)} 只是<b>排队</b>，
     * 真正的 {@link ShaderInstance} 要等 Forge 把 queue 里的 shader 全部编译/加载完，才由回调写进
     * {@link ShaderProtectionRegistry} 的缓存。事件回调期间 {@code getShader(id)} 必然为 null
     * （踩过一次：日志里那句 "missing after protected registration" 就是它）。所以改为首帧取用 + 缓存。</p>
     */
    public static ShaderInstance flame() {
        // 每帧向注册表要（一次 map 查找）：F3+T 资源重载后注册表里是新实例，
        // 若在这里缓存字段就会一直指向被 close() 的旧实例。
        ShaderInstance instance = ShaderProtectionRegistry.getShader(FLAME_ID);
        if (instance == null) return null;
        if (instance != flame) {
            flame = instance;
            if (flameType == null) {
                flameType = createFlameType(FlameShaders::flame);
            }
            LOGGER.info("Flame volume shader resolved (Iris-protected)");
        }
        return instance;
    }

    private static RenderType createFlameType(Supplier<ShaderInstance> shader) {
        ShaderStateShard shaderState = new ShaderStateShard(shader);
        // 预乘 alpha 混合：src 已含 alpha 权重 → ONE / 1-SRC_ALPHA
        TransparencyStateShard premultiplied = new TransparencyStateShard("flame_premultiplied",
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
        return create("flame_volume",
                FMT,
                VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(shaderState)
                        .setWriteMaskState(COLOR_WRITE)          // 不写深度
                        .setCullState(NO_CULL)                    // 包围盒要能从内部看
                        .setDepthTestState(LEQUAL_DEPTH_TEST)     // 地形/实体照常遮挡
                        .setTransparencyState(premultiplied)
                        .setOutputState(MAIN_TARGET)              // 保持当前目标（极佳画质下 ITEM_ENTITY_TARGET 会绑到 item-entity 缓冲）
                        .createCompositeState(false));
    }
}
