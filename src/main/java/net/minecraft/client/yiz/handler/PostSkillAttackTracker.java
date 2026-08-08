package net.minecraft.client.yiz.handler;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.RegistryObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能后首击追踪器（1.20.1 移植版）— 技能释放后标记，下一次攻击消费标记并附加伤害+回血。
 *
 * <p>伤害公式：damage_base + spell_power × damage_spell_coeff/100；回血：heal_base + max_health × heal_hp_coeff/100。</p>
 * <p>⚠️ 1.20.1 差异：用 {@link ItemStack#getAttributeModifiers(EquipmentSlot)} Multimap 遍历
 * （替代 1.21.1 DataComponents.ATTRIBUTE_MODIFIERS）。</p>
 */
public final class PostSkillAttackTracker {

    private static final Map<UUID, Boolean> MARKED = new ConcurrentHashMap<>();
    private static final Map<UUID, ItemStack> MARKED_ITEM = new ConcurrentHashMap<>();

    private PostSkillAttackTracker() {}

    public static void mark(Player player, ItemStack skillItem) {
        UUID uuid = player.getUUID();
        MARKED.put(uuid, true);
        MARKED_ITEM.put(uuid, skillItem.copy());
    }

    /** 攻击时尝试消费标记（服务端 hurt Mixin）。返回 [bonusDamage, bonusHeal]。 */
    public static float[] tryConsume(Player player) {
        UUID uuid = player.getUUID();
        if (!MARKED.getOrDefault(uuid, false)) return null;
        MARKED.remove(uuid);
        ItemStack item = MARKED_ITEM.remove(uuid);
        if (item == null || item.isEmpty()) return null;

        float damage = computeDamage(player, item);
        float heal = computeHeal(player, item);
        return new float[]{damage, heal};
    }

    private static float computeDamage(Player player, ItemStack item) {
        float base = (float) readAttr(item, YizAttributes.DAMAGE_BASE);
        double spellPow = YizAttributes.getEffectiveSpellPower(player);
        return (float)(base * spellPow / 100.0);
    }

    private static float computeHeal(Player player, ItemStack item) {
        float base = (float) readAttr(item, YizAttributes.HEAL_BASE);
        float hpCoeff = (float) readAttr(item, YizAttributes.HEAL_HP_COEFF);
        float maxHp = player.getMaxHealth();
        return base + maxHp * hpCoeff / 100f;
    }

    /** 1.20.1：遍历所有槽位的 AttributeModifier Multimap 累加目标属性值。 */
    private static double readAttr(ItemStack stack, RegistryObject<Attribute> attr) {
        if (attr == null || !attr.isPresent()) return 0;
        double val = 0;
        try {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                for (Map.Entry<Attribute, AttributeModifier> e : stack.getAttributeModifiers(slot).entries()) {
                    if (e.getKey() == attr.get()) val += e.getValue().getAmount();
                }
            }
        } catch (Throwable ignored) {}
        return val;
    }
}
