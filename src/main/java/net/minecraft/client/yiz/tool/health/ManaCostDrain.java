package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.player.Player;

/**
 * 持续法力消耗钩子 — 消费 {@link YizAttributes#MANA_COST_PER_SEC}。
 *
 * <p>本类提供通用的持续耗蓝：每 tick 读 MANA_COST_PER_SEC，每秒÷20 消耗蓝量（ManaTracker）。
 * 一次性 MANA_COST 耗蓝留给后续的技能物品系统实现。</p>
 */
public final class ManaCostDrain {

    private ManaCostDrain() {}

    /** 每 tick 调用（由 tizMod.onPlayerTick 驱动）：按 MANA_COST_PER_SEC 持续耗蓝。 */
    public static void tick(Player player) {
        if (player.level().isClientSide()) return;
        var inst = player.getAttribute(YizAttributes.MANA_COST_PER_SEC.get());
        if (inst == null || inst.getValue() <= 0) return;
        float cost = (float) inst.getValue() / 20f;
        // 法力消耗减免：MANA_COST_REDUCTION(%) 降低持续耗蓝
        var redInst = player.getAttribute(YizAttributes.MANA_COST_REDUCTION.get());
        if (redInst != null && redInst.getValue() > 0) {
            cost *= (float)(1.0 - Math.min(1.0, redInst.getValue() / 100.0));
        }
        ManaTracker.consume(player, cost);
    }
}
