package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.yiz.api.OutlineRegistry;
import net.minecraft.client.yiz.api.TargetFrameManager;
import net.minecraft.client.yiz.api.TargetFrameProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

/**
 * 锁定描边调度器（FBO + 后处理，描边已集成到实体 render）：
 * - BEFORE_ENTITIES：确保 FBO 存在；有描边实体时清空（供实体渲染阶段由 {@link LockOutlineBufferSource} 双写填充）。
 * - AFTER_ENTITIES：flush 填充缓冲到 FBO → 全屏后处理（NDC quad 采样 FBO 做边缘检测 + 发光）。
 *
 * 通用描边：任意实体实现 {@code OutlineEntity} 或经 {@link OutlineRegistry#register} 注册 Provider 即自动描边，
 * 锁定系统在此注册为动态 Provider（青色，透明度=充能进度）。
 */
public final class LockOutlineRenderer {

    /** 描边屏幕像素半径（远近一致），全局可配置。 */
    private static int postRadius = 3;
    /** 高斯宽度：越大描边越细越硬，越小发光晕越宽，全局可配置。 */
    private static float edgeSharpness = 6.0F;

    private static LockOutlineFramebuffer framebuffer;

    static {
        // 通用描边接入：锁定系统作为动态 Provider（青色，透明度=充能进度）
        OutlineRegistry.register(LockOutlineRenderer::lockOutlineColor);
    }

    private LockOutlineRenderer() {}

    /** 设置全局描边屏幕像素半径（供 {@code EntityOutline} 公开 API 调用）。 */
    public static void setPostRadius(int px) { postRadius = Math.max(1, px); }

    /** 设置全局发光锐度（越大越细越硬，越小发光晕越宽）。 */
    public static void setEdgeSharpness(float s) { edgeSharpness = Math.max(0.5f, s); }

    /** 锁定系统描边色：当前玩家锁定的目标返回青色 [0,1,1,charge]，否则 null。 */
    private static float[] lockOutlineColor(Entity entity) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        TargetFrameProvider p = TargetFrameManager.getBest(mc.player);
        if (p == null || p.getTarget(mc.player) != entity) return null;
        float charge = p.getCharge();
        if (charge <= 0) return null;
        return new float[]{0.0f, 1.0f, 1.0f, charge};
    }

    /** 供 fill RenderType 的 FILL_TARGET 绑定 FBO。 */
    static LockOutlineFramebuffer framebuffer() { return framebuffer; }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        var stage = event.getStage(); // Stage 是 final class 非枚举，不能用 switch
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // 实体渲染循环之前：确保 FBO 存在（实体渲染阶段 LockOutlineBufferSource 可能双写 fill 需要它）。
            // 无条件清空 FBO 供填充：星级描边是无条件渲染（不依赖渴攻锁定/充能），不能 gate 在锁定状态上。
            ensureFramebuffer(mc);
            framebuffer.bindAndClear();
            mc.getMainRenderTarget().bindWrite(false); // 清完绑回主缓冲，实体正常渲染不受影响
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            if (framebuffer == null) return;
            // flush 填充缓冲到 FBO（fill RenderType 的 FILL_TARGET 绑 FBO）；无条件清空缓冲
            LockOutlineBufferSource.get(mc.renderBuffers().bufferSource()).endFillBatch();
            framebuffer.unbind();
            // 本帧渲染过任意描边实体（星级/渴攻 Provider 返回非 null）才做后处理——
            // 星级描边无条件触发，渴攻描边透明度仍由 Provider 的 charge alpha 控制
            if (!LockOutlineBufferSource.consumeHasOutline()) return;
            int w = framebuffer.getWidth(), h = framebuffer.getHeight();
            if (w <= 0 || h <= 0) return;
            renderPost(mc, w, h);
        }
    }

    private static void ensureFramebuffer(Minecraft mc) {
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        if (framebuffer == null) {
            framebuffer = new LockOutlineFramebuffer(w, h);
        } else {
            framebuffer.resize(w, h);
        }
    }

    private static void renderPost(Minecraft mc, int w, int h) {
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        PoseStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushPose();
        mvStack.setIdentity();
        RenderSystem.applyModelViewMatrix();

        try {
            // 单位投影 + NDC 全屏 quad（顶点 -1..1，z=0 必然通过裁剪，绕开 ortho z 语义问题）
            RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);

            // 绑定 FBO 纹理 + uniform
            RenderSystem.setShaderTexture(0, framebuffer.getColorTextureId());
            ShaderInstance shader = LockOutlineShaders.getLockPost();
            if (shader != null) {
                if (shader.getUniform("uRadius") != null)
                    shader.getUniform("uRadius").set((float) postRadius);
                if (shader.getUniform("uSharpness") != null)
                    shader.getUniform("uSharpness").set(edgeSharpness);
                if (shader.getUniform("uTexelSize") != null)
                    shader.getUniform("uTexelSize").set(1.0f / w, 1.0f / h);
            }

            // NDC 全屏 quad（POSITION_TEX）：clip = 顶点坐标，覆盖全屏；UV v=0 底部对应 FBO 底部
            RenderType postRt = LockOutlineRenderType.post();
            var vc = mc.renderBuffers().bufferSource().getBuffer(postRt);
            vc.vertex(-1, -1, 0).uv(0, 0).endVertex();
            vc.vertex(1, -1, 0).uv(1, 0).endVertex();
            vc.vertex(1, 1, 0).uv(1, 1).endVertex();
            vc.vertex(-1, 1, 0).uv(0, 1).endVertex();
            mc.renderBuffers().bufferSource().endBatch(postRt);
        } finally {
            mvStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
        }
    }
}
