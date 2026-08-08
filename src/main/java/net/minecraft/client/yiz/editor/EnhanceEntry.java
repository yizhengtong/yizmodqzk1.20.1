package net.minecraft.client.yiz.editor;

import net.minecraft.world.item.ItemStack;

import java.util.function.ToDoubleFunction;

/**
 * 强化条目 — 属性型或标签型。
 * <p>每个技能/被动物品最多提供 6 个条目，对应 GUI 中 6 个加强槽。</p>
 *
 * <p>1.20.1 移植：{@link Attribute} 原持有 {@code editor/EditableAttribute}（1.21.1 定义，未移植），
 * 当前以解析后的 (id, displayName, getter) 三元组占位，结构等价。待 EditableAttribute 移植后
 * 改回 {@code record Attribute(EditableAttribute attr)}（key()→attr.id() 等，见各方法注释）。</p>
 */
public sealed interface EnhanceEntry {

    String key();
    String displayName();

    /** A 类：数值属性（+/- 等级，每级 +10%） */
    record Attribute(String id, String displayName,
                     ToDoubleFunction<ItemStack> getter) implements EnhanceEntry {
        @Override public String key() { return id; }
        @Override public String displayName() { return displayName; }
        /** 计算加强后的数值：base × (1 + level × 10%) */
        public double compute(ItemStack stack, int level) {
            double base = getter.applyAsDouble(stack);
            return base * (1.0 + level * 0.10);
        }
    }

    /** B 类：机制标签（0=未激活, 1=激活）。点击切换，激活时消耗经验。 */
    record Tag(String key, String displayName, String description) implements EnhanceEntry {
        @Override public String displayName() { return displayName; }
    }
}
