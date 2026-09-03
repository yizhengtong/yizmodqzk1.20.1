package net.minecraft.client.yiz.tool.abolish;

import net.minecraft.client.yiz.core.VTableReplace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VTable 入口覆写式物品功能废除（1.20.1 移植版）。
 *
 * <p>将指定 Item 子类的功能方法入口指针抄回 {@link Item} 基类的空实现，
 * 使该物品丧失对应行为（如 use/inventoryTick/onEquip），基础属性保留。</p>
 *
 * <p>核心：{@link VTableReplace#replaceMethodFromSource} 把子类 override 的
 * 方法入口覆写为基类 {@link Item} 的实现，等于"撤销 override"。</p>
 *
 * <p>描述符针对 1.20.1 Mojmap 签名核对（appendHoverText 无 TooltipContext、
 * getUseDuration 只收 ItemStack）。</p>
 */
public final class ItemAbolitionHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemAbolition");

    // 描述符常量（public 供 itemcfg 全局废除层引用）

    /** {@code InteractionResultHolder<ItemStack> use(Level, Player, InteractionHand)} */
    public static final String DESC_USE =
            "(Lnet/minecraft/world/level/Level;" +
            "Lnet/minecraft/world/entity/player/Player;" +
            "Lnet/minecraft/world/InteractionHand;)" +
            "Lnet/minecraft/world/InteractionResultHolder;";

    /** {@code InteractionResult useOn(UseOnContext)} */
    public static final String DESC_USE_ON =
            "(Lnet/minecraft/world/item/context/UseOnContext;)" +
            "Lnet/minecraft/world/InteractionResult;";

    /** {@code boolean hurtEnemy(ItemStack, LivingEntity, LivingEntity)} */
    public static final String DESC_HURT_ENEMY =
            "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/entity/LivingEntity;" +
            "Lnet/minecraft/world/entity/LivingEntity;)Z";

    /** {@code void inventoryTick(ItemStack, Level, Entity, int, boolean)} */
    public static final String DESC_INVENTORY_TICK =
            "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/level/Level;" +
            "Lnet/minecraft/world/entity/Entity;IZ)V";

    /** {@code void releaseUsing(ItemStack, Level, LivingEntity, int)} */
    public static final String DESC_RELEASE_USING =
            "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/level/Level;" +
            "Lnet/minecraft/world/entity/LivingEntity;I)V";

    /** {@code void appendHoverText(ItemStack, Level, List<Component>, TooltipFlag)}（1.20.1 无 TooltipContext） */
    public static final String DESC_APPEND_HOVER_TEXT =
            "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/level/Level;" +
            "Ljava/util/List;" +
            "Lnet/minecraft/world/item/TooltipFlag;)V";

    /** {@code void onEquip(ItemStack, EquipmentSlot, LivingEntity)} */
    public static final String DESC_ON_EQUIP =
            "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/entity/EquipmentSlot;" +
            "Lnet/minecraft/world/entity/LivingEntity;)V";

    /** {@code void onUnequip(ItemStack, EquipmentSlot, LivingEntity)} */
    public static final String DESC_ON_UNEQUIP = DESC_ON_EQUIP;

    /** {@code void onCraftedBy(ItemStack, Level, Player)} */
    public static final String DESC_ON_CRAFTED_BY =
            "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/level/Level;" +
            "Lnet/minecraft/world/entity/player/Player;)V";

    /** {@code void onEntitySwing(ItemStack, LivingEntity)} */
    public static final String DESC_ON_ENTITY_SWING =
            "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/entity/LivingEntity;)V";

    /** {@code int getUseDuration(ItemStack)}（1.20.1 只收 ItemStack） */
    public static final String DESC_GET_USE_DURATION =
            "(Lnet/minecraft/world/item/ItemStack;)I";

    /** {@code void postHurtEnemy(ItemStack, LivingEntity, LivingEntity)} */
    public static final String DESC_POST_HURT_ENEMY =
            "(Lnet/minecraft/world/item/ItemStack;" +
            "Lnet/minecraft/world/entity/LivingEntity;" +
            "Lnet/minecraft/world/entity/LivingEntity;)V";

    /** 所有可废除的功能方法 {name, desc}。 */
    public static final String[][] ALL_ITEM_METHODS = {
            {"use",             DESC_USE},
            {"useOn",           DESC_USE_ON},
            {"hurtEnemy",       DESC_HURT_ENEMY},
            {"postHurtEnemy",   DESC_POST_HURT_ENEMY},
            {"inventoryTick",   DESC_INVENTORY_TICK},
            {"releaseUsing",    DESC_RELEASE_USING},
            {"appendHoverText", DESC_APPEND_HOVER_TEXT},
            {"onEquip",         DESC_ON_EQUIP},
            {"onUnequip",       DESC_ON_UNEQUIP},
            {"onCraftedBy",     DESC_ON_CRAFTED_BY},
            {"onEntitySwing",   DESC_ON_ENTITY_SWING},
            {"getUseDuration",  DESC_GET_USE_DURATION},
    };

    private ItemAbolitionHelper() {}

    /** 彻底废除物品类的所有功能方法（VTable 层）。 */
    public static int abolishItem(Class<? extends Item> itemClass) {
        int count = 0;
        if (VTableReplace.isAvailable()) {
            for (String[] method : ALL_ITEM_METHODS) {
                if (VTableReplace.replaceMethodFromSource(itemClass, method[0], method[1], Item.class)) {
                    count++;
                }
            }
        } else {
            LOGGER.warn("VTableReplace not available for {}", itemClass.getSimpleName());
        }
        LOGGER.info("Abolished {} methods on {} (VTable={})",
                count, itemClass.getName(), VTableReplace.isAvailable());
        return count;
    }

    /** 按物品 ID 废除。 */
    public static int abolishItemById(ResourceLocation itemId) {
        Item item = BuiltInRegistries.ITEM.get(itemId);
        if (item == null) return 0;
        return abolishItem(item.getClass());
    }

    /** 只废除右键相关：use + useOn。 */
    public static int abolishRightClick(Class<? extends Item> itemClass) {
        if (!VTableReplace.isAvailable()) return 0;
        int count = 0;
        if (VTableReplace.replaceMethodFromSource(itemClass, "use", DESC_USE, Item.class)) count++;
        if (VTableReplace.replaceMethodFromSource(itemClass, "useOn", DESC_USE_ON, Item.class)) count++;
        return count;
    }

    /** 只废除攻击相关：hurtEnemy + postHurtEnemy。 */
    public static int abolishAttackEffects(Class<? extends Item> itemClass) {
        if (!VTableReplace.isAvailable()) return 0;
        int count = 0;
        if (VTableReplace.replaceMethodFromSource(itemClass, "hurtEnemy", DESC_HURT_ENEMY, Item.class)) count++;
        if (VTableReplace.replaceMethodFromSource(itemClass, "postHurtEnemy", DESC_POST_HURT_ENEMY, Item.class)) count++;
        return count;
    }

    /** 废除装备相关：onEquip + onUnequip。 */
    public static int abolishEquipEffects(Class<? extends Item> itemClass) {
        if (!VTableReplace.isAvailable()) return 0;
        int count = 0;
        if (VTableReplace.replaceMethodFromSource(itemClass, "onEquip", DESC_ON_EQUIP, Item.class)) count++;
        if (VTableReplace.replaceMethodFromSource(itemClass, "onUnequip", DESC_ON_UNEQUIP, Item.class)) count++;
        return count;
    }

    /** 废除背包 tick：inventoryTick（背包持续效果消失）。 */
    public static boolean abolishInventoryTick(Class<? extends Item> itemClass) {
        if (!VTableReplace.isAvailable()) return false;
        boolean ok = VTableReplace.replaceMethodFromSource(itemClass, "inventoryTick", DESC_INVENTORY_TICK, Item.class);
        if (ok) LOGGER.info("Abolished inventoryTick on {}", itemClass.getSimpleName());
        return ok;
    }
}
