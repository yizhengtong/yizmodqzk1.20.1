package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.editor.PoshiBypassBridge;
import net.minecraft.client.yiz.editor.PoxianDamageTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 攻击拦截 Mixin — 消费 {@link YizAttributes#POSHI}（破时）/ {@link YizAttributes#POXIAN}（破限）
 * 属性（1.21.1 移植版，仅属性驱动部分）。
 *
 * <p>完整版（1.21.1）还含"强制执行标签"系统：AttackContext/DamageTag/DirectAttackExecutor/
 * SpecialDamageAttributeRegistry/DirectHealthModExecutor（真伤/破甲/直改血量标签）。1.20.1
 * 该独立系统未移植（见 port-gap-list.md），本类只保留属性触发的破时/破限。</p>
 *
 * <p>破时（POSHI）：攻击命中时按概率清目标无敌帧（invulnerableTime=0）并开 Agent 绕过。
 * 破限（POXIAN）：按概率捕获 attack() 传入 hurt() 的原始伤害，供 LivingEntityMixin 恢复。</p>
 *
 * <p>触发概率 = 属性值%（纯属性驱动；1.21.1 另有 yizmodqzk:poshi/poxian 附魔每级+20%，
 * 1.20.1 无此附魔，故附魔加成留空）。</p>
 */
@Mixin(Player.class)
public abstract class AttackInterceptorMixin {

    /**
     * 破限：capture 传入 hurt() 的原始伤害（在执行所有减伤/cap 之前）。
     * 随后的 {@code LivingEntityMixin#yizmodqzk$modifyHurtAmount} 会按需恢复。
     */
    @org.spongepowered.asm.mixin.injection.ModifyArg(
        method = "attack",
        at = @org.spongepowered.asm.mixin.injection.At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"
        ),
        index = 1
    )
    private float yizmodqzk$captureExpectedDamage(DamageSource source, float amount) {
        Player self = (Player) (Object) this;
        if (rollTrigger(self, YizAttributes.POXIAN)) {
            PoxianDamageTracker.set(amount);
        }
        return amount;
    }

    /**
     * 破时：攻击开始即掷骰，命中则清目标无敌帧并开绕过。
     */
    @Inject(method = "attack", at = @At("HEAD"))
    private void yizmodqzk$onAttackStart(Entity target, CallbackInfo ci) {
        if (!(target instanceof LivingEntity)) return;
        Player attacker = (Player) (Object) this;
        if (rollTrigger(attacker, YizAttributes.POSHI)) {
            target.invulnerableTime = 0;
            PoshiBypassBridge.beginBypass();
        }
    }

    /**
     * 攻击完成时：清理破时/破限状态。
     */
    @Inject(method = "attack", at = @At("RETURN"))
    private void yizmodqzk$poshiAndPoxianCleanup(Entity target, CallbackInfo ci) {
        PoshiBypassBridge.endBypass();
        PoxianDamageTracker.clear();
    }

    /**
     * 掷骰子决定是否触发破时/破限。纯属性驱动：总概率 = 属性值%，cap 100%。
     * <ul>
     *   <li>属性 50 → 50%（纯属性也能触发）</li>
     *   <li>属性 100+ → 必触发</li>
     * </ul>
     */
    private static boolean rollTrigger(Player player,
                                       net.minecraftforge.registries.RegistryObject<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        if (attr == null || !attr.isPresent()) return false;
        var inst = player.getAttribute(attr.get());
        if (inst == null) return false;
        double chance = inst.getValue();   // 属性值直接当百分比
        if (chance <= 0.0) return false;
        if (chance >= 100.0) return true;
        return player.getRandom().nextFloat() < (float) (chance / 100.0);
    }
}
