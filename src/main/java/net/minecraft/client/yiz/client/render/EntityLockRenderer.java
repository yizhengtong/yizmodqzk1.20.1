package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.yiz.api.TargetFrameManager;
import net.minecraft.client.yiz.api.TargetFrameProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 锁定框渲染器（1.21.1 移植版，1.20.1 渲染 API 适配）。
 * 从 {@link TargetFrameManager} 读取最佳供应者并渲染四角锁定框（充能进度=透明度，满充能冻结旋转角）。
 */
public final class EntityLockRenderer {

    private static final double BODY_HEIGHT_FACTOR = 0.7;
    private static final float SIZE_BASE = 1.3f;
    private static final double ROTATION_SPEED = Math.PI / 6;
    private static final float CORNER_TEX_SIZE = 0.35f;

    private static final ResourceLocation[] CORNER_TEX = {
        new ResourceLocation("yizmodqzk", "textures/gui/lock_tr.png"),
        new ResourceLocation("yizmodqzk", "textures/gui/lock_tl.png"),
        new ResourceLocation("yizmodqzk", "textures/gui/lock_bl.png"),
        new ResourceLocation("yizmodqzk", "textures/gui/lock_br.png"),
    };

    private static double lastLockedAngle = 0;

    private EntityLockRenderer() {}

    /** 随距离缩放框大小（近大远小）。 */
    public static float getScaleFactor(float dist) {
        float t = Math.max(0, Math.min(12f, dist)) / 12f;
        return 0.4f + t * 0.6f;
    }

    public static double getRotationAngle() {
        return (System.currentTimeMillis() / 1000.0) * ROTATION_SPEED;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.level instanceof ClientLevel)) return;

        TargetFrameProvider provider = TargetFrameManager.getBest(mc.player);
        if (provider == null) return;
        Entity target = provider.getTarget(mc.player);
        if (target == null) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();

        Vec3 bodyCenter = new Vec3(
            target.getX(), target.getY() + target.getBbHeight() * BODY_HEIGHT_FACTOR, target.getZ());
        Vec3 forward = camPos.subtract(bodyCenter).normalize();
        Vec3 right = new Vec3(0, 1, 0).cross(forward).normalize();
        Vec3 up = forward.cross(right).normalize();

        float dist = (float) bodyCenter.distanceTo(camPos);
        float hs = SIZE_BASE * getScaleFactor(dist);
        float alpha = provider.getCharge();
        boolean ready = provider.isReady();
        if (alpha <= 0) return;

        // 满蓄力：冻结当前旋转角（继承当前位置）
        double currentAngle = getRotationAngle();
        double angle = ready ? lastLockedAngle : currentAngle;
        if (!ready) lastLockedAngle = currentAngle;
        double cosa = Math.cos(angle), sina = Math.sin(angle);
        Vec3 r = right.scale(cosa).add(up.scale(sina));
        Vec3 u = right.scale(-sina).add(up.scale(cosa));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull(); // 背面剔除会吃掉绕序反的 billboard 四边形（闪电渲染器同款）
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        ResourceLocation[] tex = provider.getCornerTextures();
        if (tex == null) tex = CORNER_TEX;

        PoseStack ps = event.getPoseStack();
        // 四角锁定框 + 中心标记（用专用 BufferBuilder，避免共享 Tesselator 与世界渲染冲突）
        float[][] corners = {{-hs, hs}, {hs, hs}, {hs, -hs}, {-hs, -hs}};
        for (int i = 0; i < 4; i++) {
            Vec3 worldPos = bodyCenter.add(r.scale(corners[i][0])).add(u.scale(corners[i][1]));
            ps.pushPose();
            ps.translate(worldPos.x - camPos.x, worldPos.y - camPos.y, worldPos.z - camPos.z);
            ps.mulPose(camera.rotation());
            RenderSystem.setShaderTexture(0, tex[i]);
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
            drawBillboardQuad(ps, CORNER_TEX_SIZE);
            ps.popPose();
        }
        // 中心标记点
        ps.pushPose();
        ps.translate(bodyCenter.x - camPos.x, bodyCenter.y - camPos.y, bodyCenter.z - camPos.z);
        ps.mulPose(camera.rotation());
        RenderSystem.setShaderTexture(0, tex[0]);
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        drawBillboardQuad(ps, 0.16f);
        ps.popPose();

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** 正对摄像机画一个带纹理的四边形（专用 BufferBuilder，1.20.1）。 */
    private static void drawBillboardQuad(PoseStack ps, float cs) {
        BufferBuilder builder = new BufferBuilder(64);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(ps.last().pose(), -cs, -cs, 0).uv(0, 0);
        builder.vertex(ps.last().pose(),  cs, -cs, 0).uv(1, 0);
        builder.vertex(ps.last().pose(),  cs,  cs, 0).uv(1, 1);
        builder.vertex(ps.last().pose(), -cs,  cs, 0).uv(0, 1);
        BufferUploader.drawWithShader(builder.end());
    }
}
