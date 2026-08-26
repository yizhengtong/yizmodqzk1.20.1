package net.minecraft.client.yiz.lightning.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.yiz.lightning.fx.ArcEffect;
import net.minecraft.client.yiz.lightning.fx.BallEffect;
import net.minecraft.client.yiz.lightning.config.SparkConfig;
import net.minecraft.client.yiz.lightning.fx.SparkEffect;
import net.minecraft.client.yiz.lightning.fx.SparkSpawner;
import net.minecraft.client.yiz.lightning.fx.SurfaceArcEffect;
import net.minecraft.client.yiz.lightning.geometry.ArcGeometry;
import net.minecraft.client.yiz.lightning.geometry.SparkGeometry;
import net.minecraft.client.yiz.lightning.orchestrate.LightningEmitter;
import net.minecraft.client.yiz.lightning.util.ShaderTimeUtil;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 闪电特效统一渲染器 — 空气电弧 + 表面游离电弧 + 球状闪电（billboard）+ Emitter 编排（tick）。
 */
public final class LightningRenderer {

    private static final List<ArcEffect> ARCS = new CopyOnWriteArrayList<>();
    private static final List<SurfaceArcEffect> SURFACE = new CopyOnWriteArrayList<>();
    private static final List<BallEffect> BALLS = new CopyOnWriteArrayList<>();
    private static final List<LightningEmitter> EMITTERS = new CopyOnWriteArrayList<>();
    private static final List<SparkEffect> SPARKS = new CopyOnWriteArrayList<>();

    /** tick 内 spawn 随机源（主线程 ClientTickEvent.Post，无需线程安全）。 */
    private static final java.util.Random RND = new java.util.Random();

    private LightningRenderer() {}

    public static void enqueue(ArcEffect e) { ARCS.add(e); }
    public static void enqueueSurface(SurfaceArcEffect e) { SURFACE.add(e); }
    public static void enqueueBall(BallEffect e) { BALLS.add(e); }
    public static void enqueueEmitter(LightningEmitter e) { EMITTERS.add(e); }
    public static void enqueueSpark(SparkEffect e) { SPARKS.add(e); }

    public static void clear() { ARCS.clear(); SURFACE.clear(); BALLS.clear(); EMITTERS.clear(); SPARKS.clear(); }

    public static void tick() {
        float dt = 0.05f;
        for (ArcEffect a : ARCS) a.life -= dt;
        ARCS.removeIf(a -> a.life <= 0f);
        float density = densityFactor();
        SURFACE.removeIf(s -> !s.tick(density));
        for (BallEffect b : BALLS) b.life -= dt;
        BALLS.removeIf(b -> b.life <= 0f);
        List<LightningEmitter> dead = new java.util.ArrayList<>();
        for (LightningEmitter e : EMITTERS) if (!e.tick(dt)) dead.add(e);
        EMITTERS.removeAll(dead);

        // ── 派生火花（SPARKS 上限 500 兜底，超限跳过生成避免失控）──
        if (SPARKS.size() < 500) {
            if (SparkConfig.isEndpoint()) {
                for (ArcEffect a : ARCS) {
                    if (a.life <= 0f) continue;
                    if (RND.nextFloat() < 0.6f) {
                        SparkSpawner.spawnEndpointSparks(a.from, a.to, a.seed, a.r, a.g, a.b);
                    }
                }
            }
            if (SparkConfig.isSurface()) {
                for (SurfaceArcEffect s : SURFACE) {
                    for (SurfaceArcEffect.MiniArc m : s.arcs) {
                        if (RND.nextFloat() < 0.15f) {
                            SparkSpawner.spawnSurfaceDrips(m.from, m.seed, s.r, s.g, s.b);
                        }
                    }
                }
            }
        }

        for (SparkEffect sp : SPARKS) sp.life -= dt;
        SPARKS.removeIf(sp -> sp.life <= 0f);
    }

    /** 密度公式：全局体表电流越多，每个的生成密度越低（密集 LOD，防密集区域爆炸）。 */
    private static float densityFactor() {
        int n = SURFACE.size();
        if (n < 4) return 1.0f;
        if (n > 16) return 0.25f;
        return (float)(1.0 - (n - 4) / 12.0 * 0.75);  // 4~16 线性衰减 1.0→0.25
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        boolean hasArc = !ARCS.isEmpty() || !SURFACE.isEmpty();
        boolean hasBall = !BALLS.isEmpty();
        boolean hasSpark = !SPARKS.isEmpty();
        if ((!hasArc && !hasBall && !hasSpark) || LightningShaders.arc == null) return;

        Vec3 cam = event.getCamera().getPosition();

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);

        // ── 电弧段（ribbon + 表面游离，arc 着色器）──
        if (hasArc) {
            RenderSystem.disableCull();
            ShaderTimeUtil.setITime(LightningShaders.arc);
            RenderSystem.setShader(() -> LightningShaders.arc);
            Matrix4f mat = event.getPoseStack().last().pose();
            float pt = event.getPartialTick();
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            boolean wrote = false;
            for (ArcEffect a : ARCS) {
                float fade = net.minecraft.util.Mth.clamp(a.life / a.maxLife, 0f, 1f);
                Vec3 from = a.fromSup != null ? a.fromSup.get(pt) : a.from;
                Vec3 to = a.toSup != null ? a.toSup.get(pt) : a.to;
                ArcGeometry.emitArc(bb, mat, from.subtract(cam), to.subtract(cam),
                        a.seed, a.width, a.r, a.g, a.b, fade);
                wrote = true;
            }
            for (SurfaceArcEffect s : SURFACE) {
                var box = s.getBox(pt);
                for (SurfaceArcEffect.MiniArc m : s.arcs) {
                    float mf = net.minecraft.util.Mth.clamp(m.life / m.maxLife, 0f, 1f);
                    ArcGeometry.emitArc(bb, mat, m.fromAt(box).subtract(cam), m.toAt(box).subtract(cam),
                            m.seed, s.halfWidth, s.r, s.g, s.b, mf);
                    wrote = true;
                }
            }
            if (wrote) {
                BufferUploader.drawWithShader(bb.end());
            } else {
                bb.end();
            }
        }

        // ── 火花段（拖尾 billboard，spark 着色器；复用 arc 段 additive blend 不切）──
        if (hasSpark && LightningShaders.spark != null) {
            ShaderTimeUtil.setITime(LightningShaders.spark);
            RenderSystem.setShader(() -> LightningShaders.spark);
            PoseStack ps = event.getPoseStack();
            Quaternionf rot = event.getCamera().rotation();
            Matrix4f mat = ps.last().pose();
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            boolean wrote = false;
            for (SparkEffect sp : SPARKS) {
                float fade = net.minecraft.util.Mth.clamp(sp.life / sp.maxLife, 0f, 1f);
                float elapsed = sp.maxLife - sp.life;
                Vec3 p = sp.pos.get(0f).subtract(cam);
                SparkGeometry.emitSpark(bb, mat, rot, p, sp.velocity, sp.size,
                        sp.r, sp.g, sp.b, fade, elapsed, sp.gravity);
                wrote = true;
            }
            if (wrote) {
                BufferUploader.drawWithShader(bb.end());
            } else {
                bb.end();
            }
        }

        // ── 球段（billboard，ball 着色器）──
        if (hasBall && LightningShaders.ball != null) {
            RenderSystem.disableCull();
            RenderSystem.defaultBlendFunc();
            ShaderTimeUtil.setITime(LightningShaders.ball);
            RenderSystem.setShader(() -> LightningShaders.ball);
            PoseStack ps = event.getPoseStack();
            Quaternionf rot = event.getCamera().rotation();
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            float pt = event.getPartialTick();
            for (BallEffect b : BALLS) {
                Vec3 c = b.pos.get(pt).subtract(cam);
                float r = b.radius;
                float fade = net.minecraft.util.Mth.clamp(b.life / b.maxLife, 0f, 1f);
                ps.pushPose();
                ps.translate(c.x, c.y, c.z);
                ps.mulPose(rot);
                Matrix4f m = ps.last().pose();
                ballVert(bb, m, -r, -r, 0, 0, fade);
                ballVert(bb, m,  r, -r, 1, 0, fade);
                ballVert(bb, m,  r,  r, 1, 1, fade);
                ballVert(bb, m, -r,  r, 0, 1, fade);
                ps.popPose();
            }
            BufferUploader.drawWithShader(bb.end());
        }

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void ballVert(VertexConsumer vc, Matrix4f m, float x, float y, float u, float v, float a) {
                vc.vertex(m, x, y, 0f).uv(u, v)
            .color(255, 255, 255, (int)(a*255)).endVertex();
    }
}
