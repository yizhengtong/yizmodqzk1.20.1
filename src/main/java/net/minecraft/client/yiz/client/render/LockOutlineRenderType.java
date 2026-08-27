package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/**
 * 锁定描边 RenderType（RenderType 子类以访问 protected shard 常量）。
 * - fill：实体穿墙纯白填充到专用 FBO（NO_DEPTH_TEST + 不透明 + 不剔除）
 * - post：全屏 quad 采样 FBO 做边缘检测 + 发光（半透明叠加）
 *
 * 关键：两者都用 KEEP_TARGET 代替默认的 MAIN_TARGET ——
 * 默认 outputState 在 endBatch 时会把 framebuffer 切回主缓冲，导致填充画不进 FBO。
 */
public class LockOutlineRenderType extends RenderType {

    /** 保持当前渲染目标（不切换 framebuffer）：填充时保持 FBO、后处理时保持主缓冲。 */
    private static final RenderStateShard.OutputStateShard KEEP_TARGET =
        new RenderStateShard.OutputStateShard("keep_target", () -> {}, () -> {});

    private LockOutlineRenderType(String name, VertexFormat format, VertexFormat.Mode mode,
                                  int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                                  Runnable setupTask, Runnable clearTask) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupTask, clearTask);
    }

    /** 填充 RenderType：穿墙（NO_DEPTH_TEST）+ 不透明 + 不剔除，NEW_ENTITY 兼容实体模型。 */
    public static RenderType fill() {
        return RenderType.create("yizxian_lock_fill",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
            256, false, false,
            CompositeState.builder()
                .setShaderState(new ShaderStateShard(LockOutlineShaders::getLockFill))
                .setTextureState(NO_TEXTURE)
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setDepthTestState(NO_DEPTH_TEST)
                .setOutputState(KEEP_TARGET)
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
