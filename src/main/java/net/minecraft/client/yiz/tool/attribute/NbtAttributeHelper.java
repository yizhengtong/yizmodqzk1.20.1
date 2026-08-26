package net.minecraft.client.yiz.tool.attribute;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 物品 NBT 属性读写（方案 A：自定义 NBT 存属性，替代原版 AttributeModifier）。
 *
 * <p>物品 NBT 键 {@code yizmodqzk:attrs}（CompoundTag: attrId → double）。
 * 优势：
 * <ul>
 *   <li>物品无原版 modifier → tooltip 不显示"穿戴于头/胸/腿/脚/主手/副手"槽位行</li>
 *   <li>属性值随物品持久化（NBT），任意槽穿戴由 onPlayerTick 聚合到实体</li>
 *   <li>多次编辑天然幂等（NBT put 覆盖）</li>
 * </ul></p>
 */
public final class NbtAttributeHelper {

    private NbtAttributeHelper() {}

    /** 物品属性 NBT 键。 */
    public static final String ATTRS_KEY = "yizmodqzk:attrs";

    /** 读物品某属性值（无 NBT/无键返回 0）。 */
    public static double get(ItemStack stack, String attrId) {
        if (stack == null || stack.isEmpty()) return 0;
        CompoundTag tag = stack.getTag();
        if (tag == null) return 0;
        CompoundTag attrs = tag.getCompound(ATTRS_KEY);
        return attrs.getDouble(attrId);
    }

    /** 写物品某属性值（值 0 也写入，显式清除；无 attrs 键时创建）。 */
    public static void set(ItemStack stack, String attrId, double value) {
        if (stack == null || stack.isEmpty()) return;
        CompoundTag tag = stack.getOrCreateTag();
        CompoundTag attrs = tag.getCompound(ATTRS_KEY);
        attrs.putDouble(attrId, value);
        tag.put(ATTRS_KEY, attrs);
    }

    /** 物品是否有任何 NBT 属性。 */
    public static boolean hasAny(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(ATTRS_KEY);
    }
}
