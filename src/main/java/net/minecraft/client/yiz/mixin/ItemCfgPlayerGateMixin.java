package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.itemcfg.FeatureType;
import net.minecraft.client.yiz.itemcfg.ItemConfigGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 实体交互 + 丢弃 + 攻击 per-player 门控（万能物品配置）。
 * 全 vanilla 目标（Player），无 @Shadow。
 */
@Mixin(Player.class)
public abstract class ItemCfgPlayerGateMixin {

    /** 对实体右键（村民菜单/物品交互）→ PASS。 */
    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateInteractOn(Entity entity, InteractionHand hand,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        if (!((Object) this instanceof ServerPlayer)) return;
        ServerPlayer sp = (ServerPlayer) (Object) this;
        if (sp.level().isClientSide()) return;
        if (ItemConfigGates.isDisabled(sp, sp.getItemInHand(hand), FeatureType.INTERACT_ENTITY)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    /**
     * 不可丢弃：只拦 2 参 drop（Q 键 + GUI 拖出权威入口）。
     * 3 参重载被死亡掉落（Inventory.dropAll）复用，拦 3 参会吞死亡掉落。
     */
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateDrop(ItemStack stack, boolean throwRandomly,
                                    CallbackInfoReturnable<ItemEntity> cir) {
        if (!((Object) this instanceof ServerPlayer)) return;
        ServerPlayer sp = (ServerPlayer) (Object) this;
        if (sp.level().isClientSide()) return;
        if (ItemConfigGates.isDisabled(sp, stack, FeatureType.UNDROPPABLE)) {
            cir.setReturnValue(null);
        }
    }

    /** 攻击目标效果 → cancel 整个攻击（服务端；客户端挥砍动画不受影响）。 */
    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateAttack(Entity target, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer)) return;
        ServerPlayer sp = (ServerPlayer) (Object) this;
        if (sp.level().isClientSide()) return;
        if (ItemConfigGates.isDisabled(sp, sp.getMainHandItem(), FeatureType.ATTACK_EFFECT)) {
            ci.cancel();
        }
    }

    /** 语义增伤：攻击者激活 DAMAGE_BOOST（adapter 声明且未关闭）→ 伤害 × factor。只读不改时原样返回，与 AttackInterceptorMixin 共存。 */
    @ModifyArg(method = "attack",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
        index = 1)
    private float yizmodqzk$gateDamageBoost(DamageSource source, float amount) {
        if (!((Object) this instanceof ServerPlayer)) return amount;
        ServerPlayer sp = (ServerPlayer) (Object) this;
        if (sp.level().isClientSide()) return amount;
        float factor = ItemConfigGates.semanticFactor(sp, "DAMAGE_BOOST");
        return factor > 0 ? amount * factor : amount;
    }
}
