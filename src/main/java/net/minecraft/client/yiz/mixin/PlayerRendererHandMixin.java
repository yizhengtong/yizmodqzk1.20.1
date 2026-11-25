package net.minecraft.client.yiz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.yiz.anim.client.AnimApplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称手臂渲染上下文：{@code PlayerRenderer#renderHand} 只渲染一只手，
 * 在它执行期间给 {@link AnimApplier} 打标记，让第三人称骨骼动画跳过
 * （第一人称由 {@code fp_*} 骨骼在 {@code ItemInHandRenderer} 里接管）。
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererHandMixin {

    private static final String RENDER_HAND =
            "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I"
                    + "Lnet/minecraft/client/player/AbstractClientPlayer;"
                    + "Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V";

    @Inject(method = RENDER_HAND, at = @At("HEAD"))
    private void yizmodqzk$beginHand(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                     AbstractClientPlayer player, ModelPart arm, ModelPart sleeve,
                                     CallbackInfo ci) {
        AnimApplier.beginHandRender();
    }

    @Inject(method = RENDER_HAND, at = @At("RETURN"))
    private void yizmodqzk$endHand(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                   AbstractClientPlayer player, ModelPart arm, ModelPart sleeve,
                                   CallbackInfo ci) {
        AnimApplier.endHandRender();
    }
}
