package net.minecraft.client.yiz.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 攻击距离 Mixin — 消费 {@link YizAttributes#ATTACK_RANGE}。
 *
 * <p>原版 1.20.1 没有可写的交互距离属性（{@code Attributes.ENTITY_INTERACTION_RANGE/
 * BLOCK_INTERACTION_RANGE} 是 1.20.2+ 才有），玩家触及距离是硬编码：方块选择走
 * {@code MultiPlayerGameMode.getPickRange()}（5.0f）。故注入该 getter：返回值 + ATTACK_RANGE。</p>
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class PlayerRangeMixin {

    @Inject(method = "getPickRange", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$extendPickRange(CallbackInfoReturnable<Float> cir) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        Player self = mc.player;
        var inst = self.getAttribute(YizAttributes.ATTACK_RANGE.get());
        if (inst != null && inst.getValue() > 0) {
            cir.setReturnValue(cir.getReturnValue() + (float) inst.getValue());
        }
    }
}
