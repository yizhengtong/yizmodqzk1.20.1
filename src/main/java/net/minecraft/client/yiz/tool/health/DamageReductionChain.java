package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 本模组实体伤害传导的 5 层减伤链。
 *
 * <p>本模组实体自管 hurt() 扣血，不走 vanilla {@code LivingEntity.hurt → actuallyHurt → setHealth}
 * 链，因此 vanilla 护甲公式与 {@code LivingEntityMixin} 注入的减伤都不会执行。本类把这些减伤
 * 显式补进传导链。</p>
 *
 * <p>顺序（与 vanilla / mixin 的层序一致）：</p>
 * <ol>
 *   <li>原版护甲减免：{@code CombatRules.getDamageAfterAbsorb}（护甲 + 韧性，BYPASSES_ARMOR 跳过）</li>
 *   <li>本模组通用防御 ARMOR 指数减免（物理伤害）</li>
 *   <li>法术防御 SPELL_DEFENSE 指数减免（非物理伤害）</li>
 *   <li>全伤害减免 DAMAGE_REDUCTION（百分比）</li>
 *   <li>伤害格挡 DAMAGE_BLOCK（固定值）</li>
 * </ol>
 *
 * <p>传导限伤（cap）由调用方在本链之后施加。</p>
 */
public final class DamageReductionChain {

    private DamageReductionChain() {}

    /** ARMOR/SPELL_DEFENSE 指数减伤参数：锚定 x=20→50%、x=50→75%（与 LivingEntityMixin 同一公式）。 */
    public static final double EXP_REDUCTION_BASE = 40.0;
    public static final double EXP_REDUCTION_EXP =
        Math.log(2.0) / Math.log(1.0 + 20.0 / EXP_REDUCTION_BASE);

    /**
     * 依次应用 5 层减伤，返回减伤后的伤害量（不小于 0）。
     *
     * @param target 受击实体
     * @param source 伤害来源（用于判定物理/法术与护甲穿透 tag）
     * @param amount 原始伤害
     */
    public static float apply(LivingEntity target, DamageSource source, float amount) {
        if (amount <= 0) return 0;

        // 1. 原版护甲减免（护甲 + 韧性）
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            amount = CombatRules.getDamageAfterAbsorb(amount,
                target.getArmorValue(),
                (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        }

        // 2/3. 通用防御 / 法术防御 指数减免：物理走 ARMOR，其余走 SPELL_DEFENSE
        Entity directHit = source.getDirectEntity();
        boolean isMelee = directHit instanceof LivingEntity && directHit == source.getEntity();
        boolean isPhysical = source.is(DamageTypeTags.IS_PROJECTILE)
            || source.is(DamageTypeTags.IS_EXPLOSION)
            || source.is(DamageTypeTags.IS_FALL)
            || isMelee;
        var expInst = target.getAttribute(
            (isPhysical ? YizAttributes.ARMOR : YizAttributes.SPELL_DEFENSE).get());
        if (expInst != null && expInst.getValue() > 0) {
            double reduction = 1.0 - Math.pow(
                1.0 + expInst.getValue() / EXP_REDUCTION_BASE,
                -EXP_REDUCTION_EXP);
            amount *= (float) (1.0 - Math.min(1.0, reduction));
        }

        // 4. 全伤害减免（百分比）
        var redInst = target.getAttribute(YizAttributes.DAMAGE_REDUCTION.get());
        if (redInst != null && redInst.getValue() > 0) {
            amount *= (float) (1.0 - Math.min(1.0, redInst.getValue() / 100.0));
        }

        // 5. 伤害格挡（固定值）
        var blockInst = target.getAttribute(YizAttributes.DAMAGE_BLOCK.get());
        if (blockInst != null && blockInst.getValue() > 0) {
            amount = Math.max(0, amount - (float) blockInst.getValue());
        }

        return Math.max(0, amount);
    }
}
