package net.minecraft.client.yiz.tool.attribute;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.registries.RegistryObject;

import java.util.UUID;

/**
 * 实体级属性读写工具（1.20.1 精简版）。
 *
 * <p>1.20.1 无 DataComponent 系统，物品级属性路径暂不移植（1.21.1 的
 * {@code DataComponents.ATTRIBUTE_MODIFIERS} 在 1.20.1 用 NBT 或 AttributeModifier API）。
 * 本类只含实体级方法（供防御属性镜像等使用）。</p>
 *
 * <p>⚠️ 1.20.1 差异：{@link AttributeModifier} 构造器 id 参数是 {@link UUID}（1.21.1 是
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
}
