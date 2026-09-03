package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.itemcfg.ItemConfigGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 语义门控：仇恨免疫（本系统施加）。
 *
 * <p>玩家激活 AGGRO_IMMUNITY（adapter 声明某物品有该功能且该玩家未关闭）
 * → {@link Mob#setTarget} 被阻止，生物不锁定该玩家。关闭开关 → 恢复。</p>
 */
@Mixin(Mob.class)
public abstract class ItemCfgMobTargetGateMixin {

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateSetTarget(LivingEntity target, CallbackInfo ci) {
        if (target == null) return;
        if (!(target instanceof ServerPlayer sp)) return;
        if (sp.level().isClientSide()) return;
        if (ItemConfigGates.hasActiveSemantic(sp, "AGGRO_IMMUNITY")) {
            ci.cancel();
        }
    }
}
