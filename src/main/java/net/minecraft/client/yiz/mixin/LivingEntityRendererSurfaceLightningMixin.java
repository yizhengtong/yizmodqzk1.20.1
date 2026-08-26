package net.minecraft.client.yiz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.yiz.api.ShockedEntityAPI;
import net.minecraft.client.yiz.lightning.render.LightningShaders;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 体表闪电叠加渲染。⚠️ 当前含临时 render 诊断日志（限频 500ms），定位"攻击感电 surface 不显示"后移除。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererSurfaceLightningMixin {


    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V",
                    shift = At.Shift.BEFORE
            ),
            require = 1
    )
    private void yizmodqzk$renderSurfaceLightning(
            LivingEntity entity, float entityYaw, float partialTick,
            PoseStack poseStack, MultiBufferSource buffer, int packedLight,
            CallbackInfo ci) {
        boolean shocked = ShockedEntityAPI.isShocked(entity.getId());
        boolean charger = false;
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            // 1.20.1：RegistryObject → .get()
            var atk = player.getAttribute(net.minecraft.client.yiz.attribute.YizAttributes.SHOCK_ATTACK.get());
            var def = player.getAttribute(net.minecraft.client.yiz.attribute.YizAttributes.SHOCK_DEFENSE.get());
            charger = (atk != null && atk.getValue() > 0.0) || (def != null && def.getValue() > 0.0);
        }
        if (!shocked && !charger) return;
        if (entity.isInvisible()) return;
        RenderType type = LightningShaders.surfaceType;
        ShaderInstance shader = LightningShaders.surface;
        if (type == null || shader == null) return;
        float fade = charger ? 0.3f : 0.0f;  // charger 兜底：低 opacity 被动带电指示；ShockedEntityAPI 主导技能触发式效果
        if (shocked) fade = Math.max(fade, ShockedEntityAPI.getFade(entity.getId()));
        if (fade <= 0f) return;
        if (shader.getUniform("iTime") != null) {
            shader.getUniform("iTime").set((float) (System.currentTimeMillis() % 100000L) / 1000.0F);
        }
        if (shader.getUniform("ColorModulator") != null) {
            shader.getUniform("ColorModulator").set(fade, fade, fade, fade);
        }

        VertexConsumer vc = buffer.getBuffer(type);
        // 1.20.1：renderToBuffer 是 8 参（1.21.1 是 4 参，颜色默认）
        ((LivingEntityRenderer) (Object) this).getModel()
                .renderToBuffer(poseStack, vc, packedLight, OverlayTexture.NO_OVERLAY,
                        fade, fade, fade, fade);
        if (buffer instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
    }
}
