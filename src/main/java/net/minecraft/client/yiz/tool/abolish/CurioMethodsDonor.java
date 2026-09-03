package net.minecraft.client.yiz.tool.abolish;

import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;

/**
 * VTable donor：Curios 饰品方法空实现（描述符匹配 ICurioItem 对应方法）。
 *
 * <p>供 {@link net.minecraft.client.yiz.itemcfg.ItemConfigAbolition} 全局废除
 * 饰品持续效果/装备/卸下/损坏时，把物品类的方法入口覆写回空实现。</p>
 */
public class CurioMethodsDonor {

    /** {@code void curioTick(SlotContext, ItemStack)} 空实现 */
    public void curioTick(SlotContext ctx, ItemStack stack) {}

    /** {@code void onEquip(SlotContext, ItemStack, ItemStack)} 空实现 */
    public void onEquip(SlotContext ctx, ItemStack prev, ItemStack stack) {}

    /** {@code void onUnequip(SlotContext, ItemStack, ItemStack)} 空实现 */
    public void onUnequip(SlotContext ctx, ItemStack prev, ItemStack stack) {}

    /** {@code void curioBreak(SlotContext, ItemStack)} 空实现 */
    public void curioBreak(SlotContext ctx, ItemStack stack) {}
}
