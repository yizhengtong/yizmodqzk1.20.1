package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;

/**
 * 锁定描边专用 FBO：把锁定实体穿墙渲染成纯色填充，供后处理 shader 采样做边缘检测。
 * 不依赖「极佳 Fabulous」画质（自己管理 FBO，普通画质可用）。
 */
public final class LockOutlineFramebuffer {

    private final RenderTarget target;

    public LockOutlineFramebuffer(int width, int height) {
        // RenderTarget 是抽象类：用匿名子类，沿用基类 createBuffers 创建标准颜色+深度 FBO
        this.target = new RenderTarget(true) { };
        this.target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        this.target.resize(width, height, Minecraft.ON_OSX);
    }

    /** 尺寸跟随窗口变化（保留颜色缓冲内容）。 */
    public void resize(int width, int height) {
        if (this.target.viewWidth != width || this.target.viewHeight != height) {
            this.target.resize(width, height, Minecraft.ON_OSX);
        }
    }

    /** 绑定 FBO 并清空颜色/深度。 */
    public void bindAndClear() {
        this.target.clear(Minecraft.ON_OSX);
        this.target.bindWrite(false);
    }

    /** 解绑，切回主渲染缓冲。 */
    public void unbind() {
        Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
    }

    public RenderTarget get() { return this.target; }

    public int getWidth() { return this.target.viewWidth; }

    public int getHeight() { return this.target.viewHeight; }

    /** FBO 颜色纹理 id，供后处理 shader 的 Sampler0 采样。 */
    public int getColorTextureId() { return this.target.getColorTextureId(); }
}
