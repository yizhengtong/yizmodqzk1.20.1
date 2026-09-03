package net.minecraft.client.yiz.itemcfg;

import com.google.common.collect.Multimap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 物品功能类型（万能物品配置）。
 *
 * <p>{@code zhName} 为内建中文名（GUI 自动汉化的功能名来源）；
 * {@code probes} 为结构检测探针（Item 方法覆写判定）。</p>
 *
 * <p>语义功能（仇恨免疫/取消受击/增伤）反射识别不了，不在此列，
 * 由 {@link AdapterRegistry} 声明。</p>
 */
public enum FeatureType {

    RIGHT_CLICK("右键使用",
            MethodProbe.of("use", InteractionResultHolder.class, Level.class, Player.class, InteractionHand.class)),
    PLACE("放置方块",
            MethodProbe.of("useOn", InteractionResult.class, UseOnContext.class)),
    INTERACT_ENTITY("对实体交互",
            MethodProbe.of("interactLivingEntity", InteractionResult.class, ItemStack.class, Player.class, LivingEntity.class, InteractionHand.class)),
    ATTACK_EFFECT("攻击目标效果",
            MethodProbe.of("hurtEnemy", boolean.class, ItemStack.class, LivingEntity.class, LivingEntity.class)),
    FOOD("食用效果",
            MethodProbe.of("finishUsingItem", ItemStack.class, ItemStack.class, Level.class, LivingEntity.class)),
    CHARGE_RELEASE("蓄力释放",
            MethodProbe.of("releaseUsing", void.class, ItemStack.class, Level.class, LivingEntity.class, int.class)),
    ATTRIBUTES("属性加成",
            MethodProbe.of("getDefaultAttributeModifiers", Multimap.class, EquipmentSlot.class)),
    INVENTORY_TICK("物品栏持续效果",
            MethodProbe.of("inventoryTick", void.class, ItemStack.class, Level.class, Entity.class, int.class, boolean.class)),
    ARMOR_TICK("穿戴效果",
            MethodProbe.of("onEquip", void.class, ItemStack.class, EquipmentSlot.class, LivingEntity.class)),
    UNDROPPABLE("不可丢弃",
            MethodProbe.of("onDroppedByPlayer", boolean.class, ItemStack.class, Player.class)),
    /** Curios 饰品槽位效果：不靠覆写 Item 方法，靠 Curios 槽位注册（CuriosBridge 检测）。 */
    CURIOS_SLOT("饰品槽位效果"),
    /** 饰品持续效果（curioTick 每 tick 施加；所有注册槽位的饰品默认有此开关）。 */
    CURIO_TICK("持续效果"),
    /** 饰品属性加成（ICurioItem.getAttributeModifiers 覆写）。 */
    CURIO_ATTRIBUTES("属性加成"),
    /** 饰品装备效果（ICurioItem.onEquip 覆写）。 */
    CURIO_ON_EQUIP("装备效果"),
    /** 饰品卸下效果（ICurioItem.onUnequip 覆写）。 */
    CURIO_ON_UNEQUIP("卸下效果"),
    /** 饰品损坏效果（ICurioItem.curioBreak 覆写）。 */
    CURIO_BREAK("损坏效果");

    private final String zhName;
    private final MethodProbe[] probes;

    FeatureType(String zhName, MethodProbe... probes) {
        this.zhName = zhName;
        this.probes = probes;
    }

    public String zhName() {
        return zhName;
    }

    /** 目标物品类是否具备该功能（结构检测）。 */
    public boolean detected(Class<?> clazz) {
        for (MethodProbe p : probes) {
            if (p.matches(clazz)) return true;
        }
        return false;
    }

    /** 由 id（枚举名）反查，容错大小写。 */
    public static FeatureType fromId(String id) {
        for (FeatureType ft : values()) {
            if (ft.name().equalsIgnoreCase(id)) return ft;
        }
        return null;
    }
}
