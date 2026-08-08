package net.minecraft.client.yiz.api;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.editor.EnhanceTagRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 主动技能物品接口。
 *
 * <p>实现此接口的 Item：</p>
 * <ul>
 *   <li>自动归入「技能」创造标签页（通过 {@link #getTabIcon}）</li>
 *   <li>可放入装载槽，按对应按键释放（通过 {@link #getCastMode} 等）</li>
 *   <li>支持强化系统：属性加强 + 触发标签（继承自 {@link IEnhanceable}）</li>
 * </ul>
 */
public interface ISkillItem extends IEnhanceable {

    /** 创造标签页图标（默认返回自身） */
    default Item getTabIcon() {
        if (this instanceof Item item) return item;
        return (Item) this;
    }

    /** 按键驱动模式 */
    SkillCastMode getCastMode(ItemStack stack);

    /** 冷却 tick 数（读物品 cooldown_value 属性，0=无冷却） */
    default int getCooldownTicks(ItemStack stack) {
        // TODO(1.20.1-port): 依赖 attribute/YizAttributes.COOLDOWN_VALUE 注册（1.21.1 id "cooldown_value"）。
        // 1.20.1 无 DataComponent（ATTRIBUTE_MODIFIERS），改用 getAttributeModifiers(EquipmentSlot)
        // 的 Multimap 遍历（每槽位累加同名属性修饰符）。
        double val = 0;
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            for (var mod : stack.getAttributeModifiers(slot).get(YizAttributes.COOLDOWN_VALUE.get())) {
                val += mod.getAmount();
            }
        }
        return (int) Math.max(0, val);
    }

    /** 连发间隔 tick 数（CONTINUOUS 用） */
    default int getCastInterval(ItemStack stack) { return 4; }

    /** 执行技能效果（服务端权威） */
    void onCast(Player player, ItemStack stack);

    /**
     * 「开启」入口：技能被成功激活时调用（通常在 onCast 内、耗蓝等前置检查通过之后）。
     * <p>默认实现：分发所有声明了 {@link SkillTriggerType#ACTIVATE} 时机、且已激活的强化标签。
     * 技能一般无需覆写，只需在 onCast 合适位置调用 {@code onActivate(slot, player, stack)} 即可。</p>
     *
     * @param slot 槽位编号（0=大槽, 1-3=技能槽）
     */
    default void onActivate(int slot, Player player, ItemStack stack) {
        if (player instanceof ServerPlayer sp) {
            EnhanceTagRegistry.executeActiveTags(sp, stack, slot, SkillTriggerType.ACTIVATE, null);
        }
    }

    /**
     * 「触发」入口：技能运行中实际造成一次效果（伤害/治疗/施加状态）时调用。
     * <p>默认实现：分发所有声明了 {@link SkillTriggerType#TRIGGER} 时机、且已激活的强化标签。
     * 纯位移等无效果技能可不调用。开关型/周期伤害型技能在每次结算效果处调用。</p>
     *
     * @param slot   槽位编号
     * @param target 本次命中的实体；无具体目标（如 AoE）时传 null
     */
    default void onTrigger(int slot, Player player, ItemStack stack, LivingEntity target) {
        if (player instanceof ServerPlayer sp) {
            EnhanceTagRegistry.executeActiveTags(sp, stack, slot, SkillTriggerType.TRIGGER, target);
        }
    }

    /**
     * 「卸载」入口：技能从装配槽被移除时调用（服务端）。
     * <p>开关型等持有持续状态的技能应覆写此方法，关闭状态、清除 transient 标记。
     * 默认空实现。由 {@code SkillConfigMenu.removed} 间接驱动——技能类在 onCast 产生效果时
     * 向 EffectRegistry 登记 onUnequip 等价清理回调。</p>
     */
    default void onUnequip(Player player, ItemStack stack) {}
}
