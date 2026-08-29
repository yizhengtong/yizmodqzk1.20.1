package net.minecraft.client.yiz.handler;

import net.minecraft.client.yiz.tool.health.BleedSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 流血 + 蓄力满增强接入：
 * <ul>
 *   <li>攻击时对目标施加属性流血（读攻击者 bleed_ratio/bleed_time/bleed_stack，未设置用默认）。</li>
 *   <li>会心/渴攻蓄力满且攻击目标 == 锁定目标 → 必定暴击 + 固定流血 30%
 *       （锁定 A 蓄力后攻击 B 不生效，视线转移即失效）。</li>
 * </ul>
 */
public final class BleedHandler {

    private BleedHandler() {}

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.getEntity().level().isClientSide) return;
        Entity src = event.getSource().getEntity();
        if (!(src instanceof LivingEntity) || !(event.getEntity() instanceof LivingEntity)) return;
        LivingEntity attacker = (LivingEntity) src;
        LivingEntity target = (LivingEntity) event.getEntity();
        // 属性流血（读攻击者属性）
        BleedSystem.applyBleed(attacker, target);
        // 会心/渴攻蓄力满（仅对锁定目标）：必暴击 + 流血（按玩家流血属性，未配置默认 30%）
        if (attacker instanceof Player) {
            Player player = (Player) attacker;
            if (LockOnHandler.isChargeReadyFor(player, target)) {
                BleedSystem.applyChargeBleed(player, target);
                LockOnHandler.markChargeCrit(player);
            }
        }
    }
}
