package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 跳跃力度 Mixin — 消费 {@link YizAttributes#JUMP_STRENGTH}（仅玩家，1.21.1 移植版）。
 *
 * <p>注入 {@link LivingEntity#getJumpPower()} RETURN：读 JUMP_STRENGTH 百分比加成，
 * {@code jumpPower × (1 + pct/100)}。</p>
 *
 * <p>JUMP_STRENGTH=50 → 跳跃力度 +50%（起跳高度约 +125%）。值域 ≥0，百分比。</p>
 */
@Mixin(LivingEntity.class)
public abstract class PlayerJumpMixin {

    @Inject(method = "getJumpPower", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$applyJumpStrength(CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Player player)) return;
        var inst = player.getAttribute(YizAttributes.JUMP_STRENGTH.get());
        if (inst == null) return;
        double pct = inst.getValue();
        if (pct <= 0) return;
        cir.setReturnValue(cir.getReturnValue() * (float) (1.0 + pct / 100.0));
    }
}
