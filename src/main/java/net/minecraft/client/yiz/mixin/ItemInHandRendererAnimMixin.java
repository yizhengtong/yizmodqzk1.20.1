package net.minecraft.client.yiz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.yiz.anim.AnimClientState;
import net.minecraft.client.yiz.anim.BoneTransform;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物品点：物品渲染到手上之前，叠加 {@code right_item} / {@code left_item} 的额外偏移/旋转/缩放。
 * 第一/第三人称都走 {@code ItemInHandRenderer#renderItem}，所以一次实现两端都生效；
 * 左右手按 {@code leftHand} 参数分别取对应手的物品点。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererAnimMixin {

    @Inject(
            method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"))
    private void yizmodqzk$itemBone(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx,
                                    boolean leftHand, PoseStack pose, MultiBufferSource buffer,
                                    int light, CallbackInfo ci) {
        if (!(entity instanceof Player player) || !AnimClientState.anyPlaying()) {
            return;
        }
        BoneTransform t = AnimClientState.sample(player.getId(), leftHand ? "left_item" : "right_item");
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
