package net.minecraft.client.yiz.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.yiz.anim.AnimClientState;
import net.minecraft.client.yiz.anim.client.AnimApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第三人称骨骼动画：在 {@code PlayerModel.setupAnim} 的 TAIL 叠加动画骨骼增量。
 * 编辑器视口渲染 {@code mc.player} 时同样走这里，所以编辑器 = 游戏内效果。
 *
 * <p>gizmo/包围盒不再在这里推导矩阵：{@code AnimSkeletonCapture} 改为在
 * {@code ModelPart#render} 里直接取 GPU 实际使用的矩阵，见 {@code ModelPartRenderCaptureMixin}。</p>
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelAnimMixin {

    /**
     * 在原版 setupAnim 之前，把上一帧叠加的动画增量撤掉。
     * 必须无条件执行（不判断 anyPlaying）：动画停止/清除预览后也要让模型回到原版姿态，
     * 尤其是原版从不重置的 {@code body.zRot}/{@code head.zRot} 等通道。
     */
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void yizmodqzk$restoreAnim(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                       float ageInTicks, float netHeadYaw, float headPitch,
                                       CallbackInfo ci) {
        AnimApplier.restore((HumanoidModel<?>) (Object) this);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void yizmodqzk$applyAnim(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                     float ageInTicks, float netHeadYaw, float headPitch,
                                     CallbackInfo ci) {
        if (!(entity instanceof Player player) || !AnimClientState.anyPlaying()
                || AnimApplier.inHandRender()) {
            // 第一人称手臂渲染（PlayerRenderer#renderHand）：只渲染一只手臂，
            // 第三人称骨骼动画不参与，第一人称由 fp_* 骨骼在 ItemInHandRenderer 里接管。
            return;
        }
        PlayerModel model = (PlayerModel) (Object) this;
        AnimApplier.apply((HumanoidModel<?>) model, player);
        // 原版在 setupAnim 里 copyFrom 过这些外层（皮肤第二层），但那时动画还没叠加；
        // 这里补一次，否则头/身/四肢动起来后外层贴图会留在原版姿态。
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightPants.copyFrom(model.rightLeg);
        model.leftPants.copyFrom(model.leftLeg);
        model.hat.copyFrom(model.head);
        model.jacket.copyFrom(model.body);
    }
}
