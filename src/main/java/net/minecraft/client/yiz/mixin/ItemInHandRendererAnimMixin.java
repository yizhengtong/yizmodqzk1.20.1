package net.minecraft.client.yiz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.yiz.anim.AnimClientState;
import net.minecraft.client.yiz.anim.BoneTransform;
import net.minecraft.client.yiz.anim.client.FirstPersonHeldItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一/第三人称手持物品动画。
 *
 * <ul>
 *   <li><b>第一人称</b>：一旦当前动画给这只手写了 {@code fp_*_arm} 关键帧，就取消原版
 *       {@code renderArmWithItem}，改走 {@link FirstPersonHeldItem}（掌心锚点 + display transform +
 *       {@code ItemDisplayContext.NONE}），和 1.21.1 版模组的做法一致。</li>
 *   <li><b>第三人称</b>：继续在 {@code renderItem} 前应用 {@code right_item}/{@code left_item} 物品点。</li>
 * </ul>
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererAnimMixin {

    private static final String RENDER_ARM =
            "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;"
                    + "FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @Inject(method = RENDER_ARM, at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$firstPersonItem(AbstractClientPlayer player, float partialTicks, float pitch,
                                           InteractionHand hand, float swingProgress, ItemStack stack,
                                           float equipProgress, PoseStack pose, MultiBufferSource buffer,
                                           int light, CallbackInfo ci) {
        if (!(player instanceof LocalPlayer local) || !AnimClientState.anyFirstPersonPlaying()) {
            return;
        }
        boolean leftHand = FirstPersonHeldItem.isLeftSide(local, hand);
        if (FirstPersonHeldItem.renderAnimated(pose, (ItemInHandRenderer) (Object) this,
                local, stack, leftHand, buffer, light)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void yizmodqzk$itemBone(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx,
                                    boolean leftHand, PoseStack pose, MultiBufferSource buffer,
                                    int light, CallbackInfo ci) {
        if (!(entity instanceof Player player)) {
            return;
        }
        if (ctx == ItemDisplayContext.NONE) {
            // 第一人称手动渲染路径（FirstPersonHeldItem）已经处理过 fp_*_item
            return;
        }
        boolean firstPerson = ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        BoneTransform t;
        if (firstPerson) {
            // 第一人称只认自己的 fp_*_item：没配置就原版，绝不回退到第三人称物品点
            if (!AnimClientState.anyFirstPersonPlaying()) {
                return;
            }
            t = AnimClientState.sampleFirstPerson(leftHand ? "fp_left_item" : "fp_right_item");
        } else {
            if (!AnimClientState.anyPlaying()) {
                return;
            }
            t = AnimClientState.sample(player.getId(), leftHand ? "left_item" : "right_item");
        }
        if (t.isZero()) {
            return;
        }
        pose.translate(t.tx(), t.ty(), t.tz());
        pose.mulPose(Axis.ZP.rotationDegrees(t.rz()));
        pose.mulPose(Axis.YP.rotationDegrees(t.ry()));
        pose.mulPose(Axis.XP.rotationDegrees(t.rx()));
        pose.scale(t.sx(), t.sy(), t.sz());
    }
}
