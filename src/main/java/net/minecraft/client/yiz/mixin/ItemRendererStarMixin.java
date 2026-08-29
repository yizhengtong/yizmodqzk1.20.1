package net.minecraft.client.yiz.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.yiz.api.ShaderManager;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 8 方向描边 + 星空渲染 — 用 putBulkData 直接写颜色。
 */
@Mixin(ItemRenderer.class)
public class ItemRendererStarMixin {

    /** 生产 SRG 环境下 @Shadow protected 方法映射不命中会崩溃，改用 MixinAccess 按签名反射调用。 */
    private void yizqzk$renderModelLists(BakedModel m, ItemStack s, int l, int o, PoseStack p, VertexConsumer v) {
        net.minecraft.client.yiz.util.MixinAccess.invoke(this, ItemRenderer.class,
            new Class[]{BakedModel.class, ItemStack.class, int.class, int.class, PoseStack.class, VertexConsumer.class},
            void.class, m, s, l, o, p, v);
    }

    private static final Vector3f[] DIRS = {
        new Vector3f( 1,  1,  1), new Vector3f(-1,  1,  1),
        new Vector3f( 1, -1,  1), new Vector3f( 1,  1, -1),
        new Vector3f(-1, -1,  1), new Vector3f(-1,  1, -1),
        new Vector3f( 1, -1, -1), new Vector3f(-1, -1, -1)
    };

    /** 描边私有 immediate 源：立即 flush 保证「描边先画 → 原版模型后画盖内部」。
     *  不依赖主缓冲 flush 时机/批次顺序——生产环境第三方模组（梦幻终焉/EndingLibrary 等 mixin
     *  ItemRenderer/LevelRenderer）可能改变主缓冲 endBatch 行为，导致半透明描边延迟到模型之后
     *  → 整体覆盖（描边盖模型）或丢失；私有源规避。 */
    private static final MultiBufferSource.BufferSource OUTLINE_BUFFER =
        MultiBufferSource.immediate(new BufferBuilder(256));

    @Inject(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$renderItemWithStar(ItemStack stack, ItemDisplayContext ctx, boolean lh,
            PoseStack ps, MultiBufferSource buf, int light, int overlay, BakedModel model, CallbackInfo ci) {
        if (stack.isEmpty() || model.isCustomRenderer() || ctx == ItemDisplayContext.GROUND) return;

        boolean gui = ctx == ItemDisplayContext.GUI;
        boolean star = ShaderManager.hasItemEffect(stack);
        int outline = net.minecraft.client.yiz.api.OutlineMarker.getLevel(stack);

        // 非星空物品且无 mb 描边 — 不停留、不描边、不 cancel
        if (!star && outline < 0) return;

        // ═══ 描边 + 原版 + Cosmic 星光 ═══
        ps.pushPose();
        BakedModel cam = net.minecraftforge.client.ForgeHooksClient.handleCameraTransforms(ps, model, ctx, lh);
        ps.translate(-0.5F, -0.5F, -0.5F);

        // ── 描边 ──（8 方向偏移 + 私有 immediate 源立即 flush：描边先画，原版模型后画覆盖内部，
        // 仅轮廓边缘露出描边色；不依赖主缓冲 flush 时机/批次顺序）
        if (buf instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
        float[] c = outline >= 0 ? getColor(outline) : getColor();
        float off = 0.01f;
        RenderType glowRt = RenderType.entityTranslucentEmissive(new net.minecraft.resources.ResourceLocation("minecraft", "textures/atlas/blocks.png"));
        VertexConsumer vc = OUTLINE_BUFFER.getBuffer(glowRt);
        for (int d = 0; d < 8; d++) {
            ps.pushPose();
            ps.translate(DIRS[d].x() * off, DIRS[d].y() * off, DIRS[d].z() * off);
            for (BakedModel pass : cam.getRenderPasses(stack, true))
                for (BakedQuad q : pass.getQuads(null, null, RandomSource.create()))
                    if (shouldRenderQuad(q, DIRS[d]))
                        vc.putBulkData(ps.last(), q, c[0], c[1], c[2], c[3], light, overlay, true);
            ps.popPose();
        }
        OUTLINE_BUFFER.endBatch(glowRt);
        ps.popPose();

        // ── 原版 + Cosmic ──
        ps.pushPose();
        BakedModel cam2 = net.minecraftforge.client.ForgeHooksClient.handleCameraTransforms(ps, model, ctx, lh);
        ps.translate(-0.5F, -0.5F, -0.5F);
        for (BakedModel pass : cam2.getRenderPasses(stack, true))
            for (RenderType rt : pass.getRenderTypes(stack, true))
                yizqzk$renderModelLists(pass, stack, light, overlay, ps, buf.getBuffer(rt));
        ShaderInstance shader = ShaderManager.getActiveItemShader();
        if (shader != null) {
            if (buf instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
            if (shader.getUniform("iTime") != null)
                shader.getUniform("iTime").set((float)(System.currentTimeMillis()%100000L)/1000f);
            ShaderManager.applyCosmicUVs(shader);
            RenderType st = gui ? ShaderManager.getItemGuiRenderType()
                : ctx.firstPerson() ? ShaderManager.getItemDirectRenderType()
                : ShaderManager.getItemEntityRenderType();
            for (BakedModel pass : cam2.getRenderPasses(stack, true))
                yizqzk$renderModelLists(pass, stack, light, overlay, ps, buf.getBuffer(st));
            if (stack.hasFoil()) {
                RenderType foil = gui ? RenderType.glint() : RenderType.entityGlint();
                for (BakedModel pass : cam2.getRenderPasses(stack, true))
                    yizqzk$renderModelLists(pass, stack, light, overlay, ps, buf.getBuffer(foil));
            }
        }
        ps.popPose();
        ci.cancel();
    }

    private static float[] getColor() {
        int p; try { p = Integer.parseInt(System.getProperty("yizxian.outline.preset", "1")); }
        catch (Exception e) { p = 1; }
        return colorFor(p);
    }

    /** mb 描边等级 → 颜色（0-5，与 preset 同映射）。 */
    private static float[] getColor(int p) {
        return colorFor(p);
    }

    private static float[] colorFor(int p) {
        // 恒定亮色（无亮度脉冲，避免描边忽明忽暗）；preset 1 保留彩虹色相流动（始终有颜色）
        if (p == 0) return new float[]{1, 1, 1, 0.9f};
        if (p == 1) return hsv((System.currentTimeMillis() % 5000) / 5000f, 0.9f, 1f);
        // 0-5 现有 preset + 6=金（自走棋星级蛋 3 星用：0白/4蓝/6金）
        float[][] cols = {{0,0,0,0},{0,0,0,0},{1,0.3f,0.3f},{0.7f,0.3f,1f},{0.3f,0.55f,1f},{0.3f,1f,0.47f},{1,0.84f,0f}};
        float[] b = cols[p >= 0 && p <= 6 ? p : 1];
        return new float[]{b[0], b[1], b[2], 0.9f};
    }
    private static float[] hsv(float h,float s,float v){
        int i=(int)(h*6); float f=h*6-i, p=v*(1-s), q=v*(1-f*s), t=v*(1-(1-f)*s);
        return switch(i%6){case 0->new float[]{v,t,p,0.9f};case 1->new float[]{q,v,p,0.9f};case 2->new float[]{p,v,t,0.9f};case 3->new float[]{p,q,v,0.9f};case 4->new float[]{t,p,v,0.9f};default->new float[]{v,p,q,0.9f};};
    }
    /** 只渲染法线朝向偏移方向的 quad（细线轮廓）。物品模型 quad 无 cullface 时从顶点算法线。 */
    private static boolean shouldRenderQuad(BakedQuad quad, Vector3f dir) {
        Direction face = quad.getDirection();
        if (face != null) {
            return dir.x() * face.getStepX() + dir.y() * face.getStepY() + dir.z() * face.getStepZ() > 0;
        }
        Vector3f n = computeNormal(quad);
        if (n == null) return false;  // 算不出法线不渲染（避免厚边糊满）
        return dir.x() * n.x() + dir.y() * n.y() + dir.z() * n.z() > 0;
    }
    /** 从 quad 前三个顶点位置叉积算单位法线（物品模型无 cullface 的兜底）。 */
    private static Vector3f computeNormal(BakedQuad quad) {
        int[] v = quad.getVertices();
        if (v.length < 16) return null;
        int stride = v.length / 4;
        float ax = Float.intBitsToFloat(v[0]);
        float ay = Float.intBitsToFloat(v[1]);
        float az = Float.intBitsToFloat(v[2]);
        float bx = Float.intBitsToFloat(v[stride]);
        float by = Float.intBitsToFloat(v[stride + 1]);
        float bz = Float.intBitsToFloat(v[stride + 2]);
        float cx = Float.intBitsToFloat(v[stride * 2]);
        float cy = Float.intBitsToFloat(v[stride * 2 + 1]);
        float cz = Float.intBitsToFloat(v[stride * 2 + 2]);
        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float wx = cx - ax, wy = cy - ay, wz = cz - az;
        float nx = uy * wz - uz * wy;
        float ny = uz * wx - ux * wz;
        float nz = ux * wy - uy * wx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return null;
        return new Vector3f(nx / len, ny / len, nz / len);
    }
}
