package net.minecraft.client.yiz.itemcfg;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Curios 饰品 API 桥（1.20.x）。
 *
 * <p>槽位注册检测 + ICurioItem 方法级功能识别。所有 Curios 类引用集中在本类，
 * 经 {@code isLoaded()} 保护 + try-catch，生产未装 Curios 时安全跳过（不触发类加载）。</p>
 */
public final class CuriosBridge {

    private CuriosBridge() {}

    public static boolean isLoaded() {
        try {
            return ModList.get().isLoaded("curios");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 物品是否注册了 Curios 饰品槽位（getItemStackSlots 非空）。 */
    public static boolean hasCurioSlots(Item item) {
        if (!isLoaded()) return false;
        try {
            ItemStack probe = new ItemStack(item);
            return !CuriosApi.getItemStackSlots(probe, false).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 检测饰品物品类覆写了哪些 ICurioItem 方法 → 方法级功能（每功能一个开关）。
     * 只对"物品类直接实现 ICurioItem"的饰品有效；capability provider 方式识别不到，
     * 但这类饰品仍会有 CURIOS_TICK 通用开关（curioTick 是统一分发机制）。
     */
    public static EnumSet<FeatureType> detectFeatures(Item item) {
        EnumSet<FeatureType> set = EnumSet.noneOf(FeatureType.class);
        if (!isLoaded()) return set;
        try {
            Class<?> clazz = item.getClass();
            if (overrides(clazz, "curioTick", SlotContext.class, ItemStack.class)) {
                set.add(FeatureType.CURIO_TICK);
            }
            if (overrides(clazz, "getAttributeModifiers", SlotContext.class, UUID.class, ItemStack.class)) {
                set.add(FeatureType.CURIO_ATTRIBUTES);
            }
            if (overrides(clazz, "onEquip", SlotContext.class, ItemStack.class, ItemStack.class)) {
                set.add(FeatureType.CURIO_ON_EQUIP);
            }
            if (overrides(clazz, "onUnequip", SlotContext.class, ItemStack.class, ItemStack.class)) {
                set.add(FeatureType.CURIO_ON_UNEQUIP);
            }
            if (overrides(clazz, "curioBreak", SlotContext.class, ItemStack.class)) {
                set.add(FeatureType.CURIO_BREAK);
            }
        } catch (Throwable t) {
            // 反射失败静默跳过
        }
        return set;
    }

    /** 物品类是否覆写了指定 ICurioItem 方法（declaringClass 不在 ICurioItem/Object）。 */
    private static boolean overrides(Class<?> clazz, String name, Class<?>... params) {
        try {
            var m = clazz.getMethod(name, params);
            Class<?> d = m.getDeclaringClass();
            return d != ICurioItem.class && d != Object.class;
        } catch (NoSuchMethodException | SecurityException e) {
            return false;
        }
    }
}
