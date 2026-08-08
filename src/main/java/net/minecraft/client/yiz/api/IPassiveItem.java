package net.minecraft.client.yiz.api;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 被动技能/饰品物品接口（1.20.1 移植版）。
 */
public interface IPassiveItem extends IEnhanceable {

    /** 每玩家 tick 调用一次（服务端） */
    void onWornTick(Player player, ItemStack stack);

    /** 玩家攻击命中时调用（服务端）。默认空。 */
    default void onAttack(Player player, ItemStack stack,
                          net.minecraft.world.entity.LivingEntity target) {}

    /** 被动从装配槽卸载时调用（服务端）。默认空。 */
    default void onUnequip(Player player, ItemStack stack) {}
}
