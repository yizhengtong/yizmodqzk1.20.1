package net.minecraft.client.yiz.attribute;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 前置库自定义属性注册中心（1.20.1 Forge 移植版）。
 *
 * <p>仅注册辖界者等自研实体所需的 17 个属性。原 1.21.1 有 90+ 属性，后续按需补。</p>
 *
 * <p>1.20.1 差异：用 {@link ForgeRegistries#ATTRIBUTES} + {@link RegistryObject}；
 * 属性同步走 {@link Attribute#setSyncable(boolean)}（1.20.1 的 RangedAttribute 继承自 Attribute，
 * 无 setSyncable 返回 RangedAttribute 的重载，链式调用后为 Attribute 类型，赋值给
 * {@code Supplier<Attribute>} 无碍）。</p>
 */
public final class YizAttributes {

    private YizAttributes() {}

    public static final DeferredRegister<Attribute> ATTRIBUTES =
        DeferredRegister.create(ForgeRegistries.ATTRIBUTES, "yizmodqzk");

    //  辖界者所需 17 属性（基值 0，数值由 EntityAttributeGate 分配）

    /** 攻击强度 — 值域 ≥0。辖界者困难模板 60。 */
    public static final RegistryObject<Attribute> ATTACK_STRENGTH =
        ATTRIBUTES.register("attack_strength",
            () -> new RangedAttribute("attribute.yizmodqzk.attack_strength", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 法术强度 — 值域 ≥0。辖界者困难模板 100。 */
    public static final RegistryObject<Attribute> SPELL_POWER =
        ATTRIBUTES.register("spell_power",
            () -> new RangedAttribute("attribute.yizmodqzk.spell_power", 100.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 全伤害 — 值域 ≥0。辖界者骨架 0。 */
    public static final RegistryObject<Attribute> GENERIC_DAMAGE =
        ATTRIBUTES.register("generic_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.generic_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 近战伤害 — 值域 ≥0。辖界者骨架 0。 */
    public static final RegistryObject<Attribute> MELEE_DAMAGE =
        ATTRIBUTES.register("melee_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.melee_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 远程伤害 — 值域 ≥0。辖界者骨架 0。 */
    public static final RegistryObject<Attribute> RANGED_DAMAGE =
        ATTRIBUTES.register("ranged_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.ranged_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 伤害减免(%) — 值域 0~100。辖界者困难模板 25。 */
    public static final RegistryObject<Attribute> DAMAGE_REDUCTION =
        ATTRIBUTES.register("damage_reduction",
            () -> new RangedAttribute("attribute.yizmodqzk.damage_reduction", 0.0, 0.0, 100.0).setSyncable(true));

    /** 格挡(固定值) — 值域 ≥0。辖界者困难模板 1。 */
    public static final RegistryObject<Attribute> DAMAGE_BLOCK =
        ATTRIBUTES.register("damage_block",
            () -> new RangedAttribute("attribute.yizmodqzk.damage_block", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 无敌帧(tick) — 值域 ≥0。辖界者困难模板 16（传导受击 CD = 此值）。 */
    public static final RegistryObject<Attribute> INVINCIBILITY_MULT =
        ATTRIBUTES.register("invincibility_mult",
            () -> new RangedAttribute("attribute.yizmodqzk.invincibility_mult", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 闪避几率(%) — 值域 0~100。辖界者骨架 0。 */
    public static final RegistryObject<Attribute> DODGE_CHANCE =
        ATTRIBUTES.register("dodge_chance",
            () -> new RangedAttribute("attribute.yizmodqzk.dodge_chance", 0.0, 0.0, 100.0).setSyncable(true));

    /** 吸血(%) — 值域 ≥0。辖界者困难模板 10。 */
    public static final RegistryObject<Attribute> LIFE_STEAL =
        ATTRIBUTES.register("life_steal",
            () -> new RangedAttribute("attribute.yizmodqzk.life_steal", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 护甲抗性(自定义) — 值域 ≥0。辖界者困难模板 15（镜像到原版 ARMOR+ARMOR_TOUGHNESS）。 */
    public static final RegistryObject<Attribute> ARMOR =
        ATTRIBUTES.register("armor",
            () -> new RangedAttribute("attribute.yizmodqzk.armor", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 法术防御 — 值域 ≥0。辖界者困难模板 15（镜像到击退韧性/免疫）。 */
    public static final RegistryObject<Attribute> SPELL_DEFENSE =
        ATTRIBUTES.register("spell_defense",
            () -> new RangedAttribute("attribute.yizmodqzk.spell_defense", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 绝妄生机率(%) — 值域 0~100。辖界者骨架 0。 */
    public static final RegistryObject<Attribute> VITALITY_SEVERANCE_RATE =
        ATTRIBUTES.register("vitality_severance_rate",
            () -> new RangedAttribute("attribute.yizmodqzk.vitality_severance_rate", 0.0, 0.0, 100.0).setSyncable(true));

    /** 绝妄生机时间(秒) — 值域 ≥0。辖界者骨架 0。 */
    public static final RegistryObject<Attribute> VITALITY_SEVERANCE_TIME =
        ATTRIBUTES.register("vitality_severance_time",
            () -> new RangedAttribute("attribute.yizmodqzk.vitality_severance_time", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 涨跌多空 — 值域 ≥0。辖界者骨架 0（派生 = 攻击力×20%）。 */
    public static final RegistryObject<Attribute> FIRST_DREAM =
        ATTRIBUTES.register("long_short",
            () -> new RangedAttribute("attribute.yizmodqzk.long_short", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 传导限伤上限(%) — 值域 ≥0。辖界者困难模板 25（每击上限 = maxHealth×cap%）。 */
    public static final RegistryObject<Attribute> CONDUCTION_CAP =
        ATTRIBUTES.register("conduction_cap",
            () -> new RangedAttribute("attribute.yizmodqzk.conduction_cap", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 血量隐匿(门控) — 值域 0~1。>0 表示实体使用 SecureHealthClosure 外部表血量。辖界者=1。 */
    public static final RegistryObject<Attribute> SECURE_PULSE =
        ATTRIBUTES.register("secure_pulse",
            () -> new RangedAttribute("attribute.yizmodqzk.secure_pulse", 0.0, 0.0, 1.0).setSyncable(true));

    //  LivingEntityMixin 需要的扩展属性（2026-08-09 补全）

    public static final RegistryObject<Attribute> CRIT_RATE =
        ATTRIBUTES.register("crit_rate",
            () -> new RangedAttribute("attribute.yizmodqzk.crit_rate", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> CRIT_DAMAGE =
        ATTRIBUTES.register("crit_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.crit_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> PRECISION =
        ATTRIBUTES.register("precision",
            () -> new RangedAttribute("attribute.yizmodqzk.precision", 0.0, 0.0, 1.0).setSyncable(true));

    public static final RegistryObject<Attribute> COMBO_RATE =
        ATTRIBUTES.register("combo_rate",
            () -> new RangedAttribute("attribute.yizmodqzk.combo_rate", 0.0, 0.0, 100.0).setSyncable(true));

    public static final RegistryObject<Attribute> COUNTER_RATE =
        ATTRIBUTES.register("counter_rate",
            () -> new RangedAttribute("attribute.yizmodqzk.counter_rate", 0.0, 0.0, 100.0).setSyncable(true));

    public static final RegistryObject<Attribute> COUNTER_VALUE =
        ATTRIBUTES.register("counter_value",
            () -> new RangedAttribute("attribute.yizmodqzk.counter_value", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> JUMP_SPEED =
        ATTRIBUTES.register("jump_speed",
            () -> new RangedAttribute("attribute.yizmodqzk.jump_speed", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> KNOCKBACK_IMMUNITY =
        ATTRIBUTES.register("knockback_immunity",
            () -> new RangedAttribute("attribute.yizmodqzk.knockback_immunity", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> PROJECTILE_IMMUNITY =
        ATTRIBUTES.register("projectile_immunity",
            () -> new RangedAttribute("attribute.yizmodqzk.projectile_immunity", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> UNDYING =
        ATTRIBUTES.register("undying",
            () -> new RangedAttribute("attribute.yizmodqzk.undying", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> MANA_COST_REDUCTION =
        ATTRIBUTES.register("mana_cost_reduction",
            () -> new RangedAttribute("attribute.yizmodqzk.mana_cost_reduction", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> MAGIC_DAMAGE =
        ATTRIBUTES.register("magic_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.magic_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> LAVA_IMMUNE_TIME =
        ATTRIBUTES.register("lava_immune_time",
            () -> new RangedAttribute("attribute.yizmodqzk.lava_immune_time", 0.0, 0.0, 100.0).setSyncable(true));

    public static final RegistryObject<Attribute> LAVA_IMMUNE_TIME_FLAT =
        ATTRIBUTES.register("lava_immune_time_flat",
            () -> new RangedAttribute("attribute.yizmodqzk.lava_immune_time_flat", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> LAVA_DAMAGE_REDUCTION =
        ATTRIBUTES.register("lava_damage_reduction",
            () -> new RangedAttribute("attribute.yizmodqzk.lava_damage_reduction", 0.0, 0.0, 100.0).setSyncable(true));

    public static final RegistryObject<Attribute> LAVA_DAMAGE_REDUCTION_FLAT =
        ATTRIBUTES.register("lava_damage_reduction_flat",
            () -> new RangedAttribute("attribute.yizmodqzk.lava_damage_reduction_flat", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> WATER_BREATH_TIME =
        ATTRIBUTES.register("water_breath_time",
            () -> new RangedAttribute("attribute.yizmodqzk.water_breath_time", 0.0, 0.0, 100.0).setSyncable(true));

    public static final RegistryObject<Attribute> WATER_BREATH_TIME_FLAT =
        ATTRIBUTES.register("water_breath_time_flat",
            () -> new RangedAttribute("attribute.yizmodqzk.water_breath_time_flat", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> ARMOR_PENETRATION =
        ATTRIBUTES.register("armor_penetration",
            () -> new RangedAttribute("attribute.yizmodqzk.armor_penetration", 0.0, 0.0, 100.0).setSyncable(true));

    public static final RegistryObject<Attribute> ARMOR_PENETRATION_FLAT =
        ATTRIBUTES.register("armor_penetration_flat",
            () -> new RangedAttribute("attribute.yizmodqzk.armor_penetration_flat", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    //  技能系统属性（2026-08-09 补全）

    public static final RegistryObject<Attribute> COOLDOWN_VALUE =
        ATTRIBUTES.register("cooldown_value",
            () -> new RangedAttribute("attribute.yizmodqzk.cooldown_value", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> MAX_CHARGES =
        ATTRIBUTES.register("max_charges",
            () -> new RangedAttribute("attribute.yizmodqzk.max_charges", 1.0, 0.0, 100.0).setSyncable(true));

    public static final RegistryObject<Attribute> SKILL_RANGE =
        ATTRIBUTES.register("skill_range",
            () -> new RangedAttribute("attribute.yizmodqzk.skill_range", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> SKILL_INTERVAL =
        ATTRIBUTES.register("skill_interval",
            () -> new RangedAttribute("attribute.yizmodqzk.skill_interval", 0.0, 0.0, 100.0).setSyncable(true));

    public static final RegistryObject<Attribute> COOLDOWN_REDUCTION =
        ATTRIBUTES.register("cooldown_reduction",
            () -> new RangedAttribute("attribute.yizmodqzk.cooldown_reduction", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> COMBO_VALUE =
        ATTRIBUTES.register("combo_value",
            () -> new RangedAttribute("attribute.yizmodqzk.combo_value", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> COMBO_COUNT =
        ATTRIBUTES.register("combo_count",
            () -> new RangedAttribute("attribute.yizmodqzk.combo_count", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> DAMAGE_BASE =
        ATTRIBUTES.register("damage_base",
            () -> new RangedAttribute("attribute.yizmodqzk.damage_base", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> DAMAGE_SPELL_COEFF =
        ATTRIBUTES.register("damage_spell_coeff",
            () -> new RangedAttribute("attribute.yizmodqzk.damage_spell_coeff", 0.0, 0.0, 100.0).setSyncable(true));

    public static final RegistryObject<Attribute> HEAL_BASE =
        ATTRIBUTES.register("heal_base",
            () -> new RangedAttribute("attribute.yizmodqzk.heal_base", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    public static final RegistryObject<Attribute> HEAL_HP_COEFF =
        ATTRIBUTES.register("heal_hp_coeff",
            () -> new RangedAttribute("attribute.yizmodqzk.heal_hp_coeff", 0.0, 0.0, 100.0).setSyncable(true));

    //  堆叠模式 + 便捷方法 

    public enum StackMode { MULTIPLY, ADD }

    private static final java.util.Map<RegistryObject<Attribute>, StackMode> STACK_MODES = new java.util.HashMap<>();

    public static void registerStackMode(RegistryObject<Attribute> attr, StackMode mode) {
        STACK_MODES.put(attr, mode);
    }

    public static StackMode getStackMode(RegistryObject<Attribute> attr) {
        return STACK_MODES.getOrDefault(attr, StackMode.MULTIPLY);
    }

    public static double getEffectiveSpellPower(net.minecraft.world.entity.LivingEntity entity) {
        var sp = entity.getAttribute(SPELL_POWER.get());
        double base = sp != null ? sp.getValue() : 0;
        var md = entity.getAttribute(MAGIC_DAMAGE.get());
        double boost = md != null ? md.getValue() : 0;
        return base * (1.0 + boost / 100.0);
    }
}
