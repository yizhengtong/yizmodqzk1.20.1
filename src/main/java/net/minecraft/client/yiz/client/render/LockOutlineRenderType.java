package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 锁定描边 RenderType（RenderType 子类以访问 protected shard 常量）。
 * - fill(tex)：实体穿墙纯白填充到专用 FBO（NO_DEPTH_TEST + 带实体纹理做 alpha 过滤 + FILL_TARGET 绑 FBO）
 * - post：全屏 quad 采样 FBO 做边缘检测 + 发光（KEEP_TARGET 保持当前渲染目标）
 */
public class LockOutlineRenderType extends RenderType {

    /** 保持当前渲染目标（后处理用：保持主缓冲，不被默认 MAIN_TARGET 切走）。 */
    private static final RenderStateShard.OutputStateShard KEEP_TARGET =
        new RenderStateShard.OutputStateShard("keep_target", () -> {}, () -> {});

    /** 填充时切换到专用 FBO（fill flush 时绑定）；clear 切回主缓冲——fill buffer 若中途自动刷帧
     *  （BufferBuilder 满触发 immediate.endBatch）也必须切回主缓冲，否则后续实体本体 normal 渲染
     *  写进 FBO 而非主缓冲 → 实体本体不渲染、只剩描边（描边有但实体消失）。 */
    private static final RenderStateShard.OutputStateShard FILL_TARGET =
        new RenderStateShard.OutputStateShard("lock_fill_target",
            () -> LockOutlineRenderer.framebuffer().get().bindWrite(false),
            () -> net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().bindWrite(false));

    /** 按实体纹理缓存 fill RenderType（实体纹理数量有限，避免每帧新建）。 */
    private static final Map<ResourceLocation, RenderType> FILL_CACHE = new HashMap<>();

    private LockOutlineRenderType(String name, VertexFormat format, VertexFormat.Mode mode,
                                  int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                                  Runnable setupTask, Runnable clearTask) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupTask, clearTask);
    }

    /** 填充 RenderType：带实体纹理（fill fsh 采样 alpha 过滤镂空区）+ 穿墙 + 绑 FBO。 */
    public static RenderType fill(ResourceLocation texture) {
        return FILL_CACHE.computeIfAbsent(texture, LockOutlineRenderType::createFill);
    }

    private static RenderType createFill(ResourceLocation texture) {
        return RenderType.create("yizxian_lock_fill",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
            256, false, false,
            CompositeState.builder()
                .setShaderState(new ShaderStateShard(LockOutlineShaders::getLockFill))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setDepthTestState(NO_DEPTH_TEST)
                .setOutputState(FILL_TARGET)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false));
    }

    /** 后处理 RenderType：全屏 quad 采样 FBO 纹理，半透明叠加描边。 */
    public static RenderType post() {
        return RenderType.create("yizxian_lock_post",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS,
            256, false, false,
            CompositeState.builder()
                .setShaderState(new ShaderStateShard(LockOutlineShaders::getLockPost))
                .setTextureState(NO_TEXTURE)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setDepthTestState(NO_DEPTH_TEST)
                .setOutputState(KEEP_TARGET)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false));
    }
}
