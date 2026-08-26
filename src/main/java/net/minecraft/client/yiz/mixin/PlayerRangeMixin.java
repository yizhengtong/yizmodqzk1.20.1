package net.minecraft.client.yiz.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 攻击距离 Mixin — 消费 {@link YizAttributes#ATTACK_RANGE}（1.21.1 移植版）。
 *
 * <p>1.21.1 用 mirrorAttackRange 把 ATTACK_RANGE 写入原版
 * {@code Attributes.ENTITY_INTERACTION_RANGE/BLOCK_INTERACTION_RANGE}，但这两个属性
 * 是 1.20.2+ 才有。1.20.1 玩家触及距离硬编码：方块选择用
 * {@code MultiPlayerGameMode.getPickRange()}（5.0f）。故注入该 getter：返回值 + ATTACK_RANGE。
 * （服务端破坏距离/实体攻击距离的 1.20.1 硬编码扩展见 port-gap-list.md #13。）</p>
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
