package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.yiz.api.TargetFrameManager;
import net.minecraft.client.yiz.api.TargetFrameProvider;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

/**
 * 锁定目标青色发光描边渲染器（FBO + 后处理版，复刻原版光灵箭 glow）：
 * 1. 把锁定实体穿墙（NO_DEPTH_TEST）渲染成纯白填充到专用 FBO。
 * 2. 全屏后处理：采样 FBO 做「邻域填充密度高斯」边缘检测 + 发光，青色叠加到主缓冲。
 * 描边宽度 = 屏幕固定像素（POST_RADIUS），远近一致；穿墙；不依赖 Fabulous 画质。
 */
public final class LockOutlineRenderer {

    /** 描边屏幕像素半径（远近一致）。 */
    private static final int POST_RADIUS = 3;
    /** 高斯宽度：越大描边越细越硬，越小发光晕越宽。 */
    private static final float EDGE_SHARPNESS = 6.0F;

    private static LockOutlineFramebuffer framebuffer;

    private LockOutlineRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        TargetFrameProvider provider = TargetFrameManager.getBest(mc.player);
        if (provider == null) return;
        Entity target = provider.getTarget(mc.player);
        if (target == null) return;
        float charge = provider.getCharge();
        if (charge <= 0) return;

        ensureFramebuffer(mc);
        int w = framebuffer.getWidth(), h = framebuffer.getHeight();
        if (w <= 0 || h <= 0) return;

        var camera = event.getCamera();
        double cx = camera.getPosition().x, cy = camera.getPosition().y, cz = camera.getPosition().z;
        float partialTick = event.getPartialTick();
        int light = 0xF000F0;
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource real = mc.renderBuffers().bufferSource();

        // ── pass 1：穿墙纯白填充到 FBO（用 EntityRenderer.render，跳过 shadow/火焰/hitbox）──
        RenderType fillRt = LockOutlineRenderType.fill();
        MultiBufferSource fillSource = (type) -> real.getBuffer(fillRt);
        framebuffer.bindAndClear();
        try {
            poseStack.pushPose();
            poseStack.translate(target.getX() - cx, target.getY() - cy, target.getZ() - cz);
            EntityRenderer er = (EntityRenderer) mc.getEntityRenderDispatcher().getRenderer(target);
            if (er != null) {
                // 绑定实体主纹理，供 fill shader 采样 alpha 过滤透明镂空区
                try { RenderSystem.setShaderTexture(0, er.getTextureLocation(target)); }
                catch (Throwable ignored) {}
                er.render(target, target.getYRot(), partialTick, poseStack, fillSource, light);
            }
            poseStack.popPose();
            real.endBatch(fillRt);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        framebuffer.unbind();

        // ── pass 2：全屏后处理（边缘检测 + 发光）叠加到主缓冲 ──
        renderPost(mc, w, h, charge);
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

    private static void renderPost(Minecraft mc, int w, int h, float charge) {
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
                if (shader.getUniform("uLockColor") != null)
                    shader.getUniform("uLockColor").set(0.0f, 1.0f, 1.0f, charge);
                if (shader.getUniform("uRadius") != null)
                    shader.getUniform("uRadius").set((float) POST_RADIUS);
                if (shader.getUniform("uSharpness") != null)
                    shader.getUniform("uSharpness").set(EDGE_SHARPNESS);
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
