package net.minecraft.client.yiz.tool.attribute;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 实体级属性读写工具（1.20.1 精简版）。
 *
 * <p>1.20.1 无 DataComponent 系统，物品级属性路径暂不移植（1.21.1 的
 * {@code DataComponents.ATTRIBUTE_MODIFIERS} 在 1.20.1 用 NBT 或 AttributeModifier API）。
 * 本类只含实体级方法（供防御属性镜像等使用）。</p>
 *
 * <p> 1.20.1 差异：{@link AttributeModifier} 构造器 id 参数是 {@link UUID}（1.21.1 是
 * {@code ResourceLocation}）。用确定性 UUID（{@link UUID#nameUUIDFromBytes} 由 idKey 派生），
 * 保证同 idKey 幂等（先 remove 再 add）。</p>
 */
public final class ItemAttributeHandler {

    private ItemAttributeHandler() {}

    /** 由 idKey 派生确定性 UUID（同一 idKey 永远同一 UUID，保证 remove 幂等）。 */
    public static UUID modifierUuid(String idKey) {
        return UUID.nameUUIDFromBytes(("yizmodqzk:entity_" + idKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 实体自定义属性挂载（entity_ 前缀 modifier UUID，非受保护，供镜像/临时 buff 用）。 */
    public static void setEntityAttribute(LivingEntity entity, Attribute attribute,
                                          String idKey, double value, AttributeModifier.Operation op) {
        if (attribute == null) return;
        var inst = entity.getAttribute(attribute);
        if (inst == null) return;
        UUID id = modifierUuid(idKey);
        inst.removeModifier(id);
        if (value != 0.0 && op != null) {
            // 1.20.1 构造器签名：(UUID, String name, double, Operation)
            inst.addPermanentModifier(new AttributeModifier(id, "yizmodqzk:entity_" + idKey, value, op));
        }
    }

    /** RegistryObject 重载。 */
    public static void setEntityAttribute(LivingEntity entity, RegistryObject<Attribute> attribute,
                                          String idKey, double value, AttributeModifier.Operation op) {
        if (attribute != null && attribute.isPresent()) {
            setEntityAttribute(entity, attribute.get(), idKey, value, op);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  item 级属性（1.21.1 DataComponents → 1.20.1 Modifier API）
    // ═══════════════════════════════════════════════════════════

    /**
     * 给 ItemStack 设置属性 modifier（1.20.1：addAttributeModifier 到全部装备槽，幂等）。
     * <p>1.20.1 无 removeAttributeModifier，靠原版 AttributeMap 按 modifier id 去重保证
     * 幂等（重复 add 同 id，穿戴时只生效一个）；NBT 可能累积同 id modifier，读值时按 id 去重。</p>
     */
    public static void setVanillaModifier(ItemStack stack, Attribute attribute, String idKey, double value) {
        if (attribute == null) return;
        UUID id = modifierUuid(idKey);
        // value==0 也 add（1.20.1 无 removeAttributeModifier；add 0 值同 id modifier 覆盖旧的，
        // AttributeMap 按 id 去重生效 0 = 清除属性；sumVanillaModifier 同 id 取最新 0 → 显示 0）
        AttributeModifier mod = new AttributeModifier(id, idKey, value, AttributeModifier.Operation.ADDITION);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            stack.addAttributeModifier(attribute, mod, slot);
        }
    }

    /**
     * 汇总物品上某属性的所有 modifier 值。
     * <p>1.20.1 无 removeAttributeModifier，重复编辑会累积同 id modifier（AttributeMap 去重
     * 只生效最后 add 的一个）。故这里同 id 取**最新**值（最后一次编辑），不同 id 累加。</p>
     */
    public static double sumVanillaModifier(ItemStack stack, Attribute attribute) {
        if (attribute == null) return 0;
        java.util.Map<UUID, Double> byId = new java.util.HashMap<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            for (AttributeModifier mod : stack.getAttributeModifiers(slot).get(attribute)) {
                byId.put(mod.getId(), mod.getAmount()); // 同 id 覆盖 → 保留最后一个（最新）
            }
        }
        double total = 0;
        for (double v : byId.values()) total += v;
        return total;
    }

    /** 给物品设置 yiz 自定义属性 modifier（幂等，同 id 去重）。 */
    public static void setYizModifier(ItemStack stack, Attribute attribute, String idKey, double value) {
        setVanillaModifier(stack, attribute, idKey, value);
    }

    /** 耐久值：最大耐久（1.20.1 getMaxDamage）。 */
    public static int getMaxDurability(ItemStack stack) {
        return stack.getMaxDamage();
    }

    /** 设置最大耐久（不低当前损伤）。 */
    public static void setMaxDurability(ItemStack stack, int value) {
        if (stack.getDamageValue() > value) {
            stack.setDamageValue(value);
        }
    }

    /** 增加最大耐久。 */
    public static void addMaxDurability(ItemStack stack, int delta) {
        int current = stack.getMaxDamage();
        int newValue = current + delta; // 忽略 1.21.1 的 Math.addExact 溢出防护
        setMaxDurability(stack, Math.max(1, newValue));
    }
}
