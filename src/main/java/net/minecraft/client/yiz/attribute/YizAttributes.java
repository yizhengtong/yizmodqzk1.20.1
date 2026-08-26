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

    /** 灭在多空 — 百分比真实伤害，值域 ≥0。1 点 = 目标最大生命 1%（走涨跌多空真实伤害链）。 */
    public static final RegistryObject<Attribute> DREAM_PERCENT =
        ATTRIBUTES.register("dream_percent",
            () -> new RangedAttribute("attribute.yizmodqzk.dream_percent", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

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

    //  1.21.1 移植属性（2026-08-26）：组A 死属性 7 + 组B 玩家向独立 24

    //  === 组A 死属性（1.21.1 中为注册未接线的预留属性，只注册+挂载）===

    /** 护盾值 — 值域 ≥0。护盾实际由 ShieldTracker 系统承担，属性本身 1.21.1 无消费。 */
    public static final RegistryObject<Attribute> SHIELD_VALUE =
        ATTRIBUTES.register("shield_value",
            () -> new RangedAttribute("attribute.yizmodqzk.shield_value", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 伤害类型(0物理/1-5元素) — 值域 0~5。仅 HUD 显示图标，无伤害系统读取。 */
    public static final RegistryObject<Attribute> DAMAGE_TYPE =
        ATTRIBUTES.register("damage_type",
            () -> new RangedAttribute("attribute.yizmodqzk.damage_type", 0.0, 0.0, 5.0).setSyncable(true));

    /** 治疗攻击系数(%) — 值域 0~100。1.21.1 未挂载未消费，死属性。 */
    public static final RegistryObject<Attribute> HEAL_ATK_COEFF =
        ATTRIBUTES.register("heal_atk_coeff",
            () -> new RangedAttribute("attribute.yizmodqzk.heal_atk_coeff", 0.0, 0.0, 100.0).setSyncable(true));

    /** 治疗法强系数(%) — 值域 0~100。1.21.1 未挂载未消费，死属性。 */
    public static final RegistryObject<Attribute> HEAL_SPELL_COEFF =
        ATTRIBUTES.register("heal_spell_coeff",
            () -> new RangedAttribute("attribute.yizmodqzk.heal_spell_coeff", 0.0, 0.0, 100.0).setSyncable(true));

    /** 飞行时间 — 值域 ≥0。1.21.1 无运行时消费（飞行由 FlightAbilityRegistry 驱动），预留。 */
    public static final RegistryObject<Attribute> FLIGHT_TIME =
        ATTRIBUTES.register("flight_time",
            () -> new RangedAttribute("attribute.yizmodqzk.flight_time", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 最大哨兵数 — 值域 ≥0。1.21.1 无任何哨兵实体读取，死属性。 */
    public static final RegistryObject<Attribute> MAX_SENTRIES =
        ATTRIBUTES.register("max_sentries",
            () -> new RangedAttribute("attribute.yizmodqzk.max_sentries", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 受伤触发通知次数 — 值域 ≥0。1.21.1 无事件处理读取，死属性。 */
    public static final RegistryObject<Attribute> ON_HURT =
        ATTRIBUTES.register("on_hurt",
            () -> new RangedAttribute("attribute.yizmodqzk.on_hurt", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    //  === 组B 玩家向独立属性（消费逻辑在阶段 3 接线）===

    /** 移动速度(%) — 值域 ≥0。walkSpeed×(1+move/100)。 */
    public static final RegistryObject<Attribute> MOVE_SPEED =
        ATTRIBUTES.register("move_speed",
            () -> new RangedAttribute("attribute.yizmodqzk.move_speed", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 最大疾跑速度(%) — 值域 ≥0。疾跑 ramp 叠加。 */
    public static final RegistryObject<Attribute> MAX_RUN_SPEED =
        ATTRIBUTES.register("max_run_speed",
            () -> new RangedAttribute("attribute.yizmodqzk.max_run_speed", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 空中移速(%) — 值域 ≥0。反解空气摩擦系数预乘 deltaMovement。 */
    public static final RegistryObject<Attribute> AIR_SPEED =
        ATTRIBUTES.register("air_speed",
            () -> new RangedAttribute("attribute.yizmodqzk.air_speed", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 跳跃力度(%) — 值域 ≥0。jumpPower×(1+pct/100)。 */
    public static final RegistryObject<Attribute> JUMP_STRENGTH =
        ATTRIBUTES.register("jump_strength",
            () -> new RangedAttribute("attribute.yizmodqzk.jump_strength", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 跌落安全减免(固定值) — 值域 ≥0。dmg - FALL_SAFE。 */
    public static final RegistryObject<Attribute> FALL_SAFE =
        ATTRIBUTES.register("fall_safe",
            () -> new RangedAttribute("attribute.yizmodqzk.fall_safe", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 跌落伤害减免(%) — 值域 ≥0。×(1-min(1,FALL_REDUCE/100))。 */
    public static final RegistryObject<Attribute> FALL_REDUCE =
        ATTRIBUTES.register("fall_reduce",
            () -> new RangedAttribute("attribute.yizmodqzk.fall_reduce", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 挖掘等级(0木/1石/2铁/3钻/4下界合金) — 值域 ≥0。基础速度 2+level×2。 */
    public static final RegistryObject<Attribute> MINING_LEVEL =
        ATTRIBUTES.register("mining_level",
            () -> new RangedAttribute("attribute.yizmodqzk.mining_level", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 镐挖掘覆盖(0/1) — 值域 0~1。对照 BlockTags.MINEABLE_WITH_PICKAXE。 */
    public static final RegistryObject<Attribute> MINING_PICKAXE =
        ATTRIBUTES.register("mining_pickaxe",
            () -> new RangedAttribute("attribute.yizmodqzk.mining_pickaxe", 0.0, 0.0, 1.0).setSyncable(true));

    /** 斧挖掘覆盖(0/1) — 值域 0~1。对照 MINEABLE_WITH_AXE。 */
    public static final RegistryObject<Attribute> MINING_AXE =
        ATTRIBUTES.register("mining_axe",
            () -> new RangedAttribute("attribute.yizmodqzk.mining_axe", 0.0, 0.0, 1.0).setSyncable(true));

    /** 锹挖掘覆盖(0/1) — 值域 0~1。对照 MINEABLE_WITH_SHOVEL。 */
    public static final RegistryObject<Attribute> MINING_SHOVEL =
        ATTRIBUTES.register("mining_shovel",
            () -> new RangedAttribute("attribute.yizmodqzk.mining_shovel", 0.0, 0.0, 1.0).setSyncable(true));

    /** 全类型挖掘覆盖(0/1) — 值域 0~1。全部方块可挖掘。 */
    public static final RegistryObject<Attribute> MINING_ALL =
        ATTRIBUTES.register("mining_all",
            () -> new RangedAttribute("attribute.yizmodqzk.mining_all", 0.0, 0.0, 1.0).setSyncable(true));

    /** 挖掘惩罚免疫(0/1) — 值域 0~1。免疫空中/水中减速与挖掘疲劳。 */
    public static final RegistryObject<Attribute> MINING_PENALTY_IMMUNITY =
        ATTRIBUTES.register("mining_penalty_immunity",
            () -> new RangedAttribute("attribute.yizmodqzk.mining_penalty_immunity", 0.0, 0.0, 1.0).setSyncable(true));

    /** 挖掘效率(%) — 值域 ≥0。×(1+eff/100)。 */
    public static final RegistryObject<Attribute> MINING_EFFICIENCY =
        ATTRIBUTES.register("mining_efficiency",
            () -> new RangedAttribute("attribute.yizmodqzk.mining_efficiency", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 最大法力 — 值域 ≥0。默认 200。ManaTracker.getMax 读取。 */
    public static final RegistryObject<Attribute> MAX_MANA =
        ATTRIBUTES.register("max_mana",
            () -> new RangedAttribute("attribute.yizmodqzk.max_mana", 200.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 法力回复 — 值域 ≥0。默认 1。tickRegen 公式 regen = MANA_REGEN×0.05 + ... */
    public static final RegistryObject<Attribute> MANA_REGEN =
        ATTRIBUTES.register("mana_regen",
            () -> new RangedAttribute("attribute.yizmodqzk.mana_regen", 1.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 法力回复百分比(%) — 值域 0~100。按最大法力百分比回复。 */
    public static final RegistryObject<Attribute> MANA_REGEN_PCT =
        ATTRIBUTES.register("mana_regen_pct",
            () -> new RangedAttribute("attribute.yizmodqzk.mana_regen_pct", 0.0, 0.0, 100.0).setSyncable(true));

    /** 生命回复 — 值域 ≥0。regen = LIFE_REGEN_RATE×0.05 + maxHealth×LIFE_REGEN_PCT×0.0005。 */
    public static final RegistryObject<Attribute> LIFE_REGEN_RATE =
        ATTRIBUTES.register("life_regen_rate",
            () -> new RangedAttribute("attribute.yizmodqzk.life_regen_rate", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 生命回复百分比 — 值域 ≥0。 */
    public static final RegistryObject<Attribute> LIFE_REGEN_PCT =
        ATTRIBUTES.register("life_regen_pct",
            () -> new RangedAttribute("attribute.yizmodqzk.life_regen_pct", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 破时(%) — 值域 0~100。攻击时无视目标无敌帧概率。 */
    public static final RegistryObject<Attribute> POSHI =
        ATTRIBUTES.register("poshi",
            () -> new RangedAttribute("attribute.yizmodqzk.poshi", 0.0, 0.0, 100.0).setSyncable(true));

    /** 破限(%) — 值域 0~100。攻击时忽略护甲/减伤概率。 */
    public static final RegistryObject<Attribute> POXIAN =
        ATTRIBUTES.register("poxian",
            () -> new RangedAttribute("attribute.yizmodqzk.poxian", 0.0, 0.0, 100.0).setSyncable(true));

    /** 弹射物反射半径 — 值域 ≥0。命中时转移投射物所有权并重定向。 */
    public static final RegistryObject<Attribute> PROJECTILE_REFLECTION =
        ATTRIBUTES.register("projectile_reflection",
            () -> new RangedAttribute("attribute.yizmodqzk.projectile_reflection", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 无碰撞(0/1) — 值域 ≥0。>0 无视实体碰撞。 */
    public static final RegistryObject<Attribute> NO_COLLISION =
        ATTRIBUTES.register("no_collision",
            () -> new RangedAttribute("attribute.yizmodqzk.no_collision", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 攻击距离 — 值域 ≥0。镜像到原版 ENTITY_INTERACTION_RANGE+BLOCK_INTERACTION_RANGE。 */
    public static final RegistryObject<Attribute> ATTACK_RANGE =
        ATTRIBUTES.register("attack_range",
            () -> new RangedAttribute("attribute.yizmodqzk.attack_range", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 自动攻击(0/1) — 值域 0~1。按住攻击键+冷却满+有目标自动攻击。 */
    public static final RegistryObject<Attribute> AUTO_ATTACK =
        ATTRIBUTES.register("auto_attack",
            () -> new RangedAttribute("attribute.yizmodqzk.auto_attack", 0.0, 0.0, 1.0).setSyncable(true));

    //  === 组C 状态五系 22 属性（2026-08-26 阶段4；消费在 StatusEffectDispatcher 全量版）===

    /** 眩晕攻击概率(%) — 值域 0~100。攻击者携带，命中按概率对目标施加眩晕。 */
    public static final RegistryObject<Attribute> STUN_ATTACK =
        ATTRIBUTES.register("stun_attack",
            () -> new RangedAttribute("attribute.yizmodqzk.stun_attack", 0.0, 0.0, 100.0).setSyncable(true));
    /** 减速攻击概率(%) — 值域 0~100。 */
    public static final RegistryObject<Attribute> SLOW_ATTACK =
        ATTRIBUTES.register("slow_attack",
            () -> new RangedAttribute("attribute.yizmodqzk.slow_attack", 0.0, 0.0, 100.0).setSyncable(true));
    /** 冰冻攻击概率(%) — 值域 0~100。 */
    public static final RegistryObject<Attribute> FREEZE_ATTACK =
        ATTRIBUTES.register("freeze_attack",
            () -> new RangedAttribute("attribute.yizmodqzk.freeze_attack", 0.0, 0.0, 100.0).setSyncable(true));
    /** 感电攻击概率(%) — 值域 0~100。 */
    public static final RegistryObject<Attribute> SHOCK_ATTACK =
        ATTRIBUTES.register("shock_attack",
            () -> new RangedAttribute("attribute.yizmodqzk.shock_attack", 0.0, 0.0, 100.0).setSyncable(true));
    /** 击飞攻击概率(%) — 值域 0~100。 */
    public static final RegistryObject<Attribute> KNOCKBACK_ATTACK =
        ATTRIBUTES.register("knockback_attack",
            () -> new RangedAttribute("attribute.yizmodqzk.knockback_attack", 0.0, 0.0, 100.0).setSyncable(true));
    /** 眩晕防御概率(%) — 值域 0~100。受击者携带，被击时对攻击者施加眩晕。 */
    public static final RegistryObject<Attribute> STUN_DEFENSE =
        ATTRIBUTES.register("stun_defense",
            () -> new RangedAttribute("attribute.yizmodqzk.stun_defense", 0.0, 0.0, 100.0).setSyncable(true));
    /** 减速防御概率(%) — 值域 0~100。 */
    public static final RegistryObject<Attribute> SLOW_DEFENSE =
        ATTRIBUTES.register("slow_defense",
            () -> new RangedAttribute("attribute.yizmodqzk.slow_defense", 0.0, 0.0, 100.0).setSyncable(true));
    /** 冰冻防御概率(%) — 值域 0~100。 */
    public static final RegistryObject<Attribute> FREEZE_DEFENSE =
        ATTRIBUTES.register("freeze_defense",
            () -> new RangedAttribute("attribute.yizmodqzk.freeze_defense", 0.0, 0.0, 100.0).setSyncable(true));
    /** 感电防御概率(%) — 值域 0~100。 */
    public static final RegistryObject<Attribute> SHOCK_DEFENSE =
        ATTRIBUTES.register("shock_defense",
            () -> new RangedAttribute("attribute.yizmodqzk.shock_defense", 0.0, 0.0, 100.0).setSyncable(true));
    /** 击飞防御概率(%) — 值域 0~100。 */
    public static final RegistryObject<Attribute> KNOCKBACK_DEFENSE =
        ATTRIBUTES.register("knockback_defense",
            () -> new RangedAttribute("attribute.yizmodqzk.knockback_defense", 0.0, 0.0, 100.0).setSyncable(true));
    /** 眩晕时长(tick) — 值域 ≥0。缺省 40。 */
    public static final RegistryObject<Attribute> STUN_TIME =
        ATTRIBUTES.register("stun_time",
            () -> new RangedAttribute("attribute.yizmodqzk.stun_time", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 减速百分比 — 值域 ≥0。slow_time 即减速%（缺省 40）。 */
    public static final RegistryObject<Attribute> SLOW_TIME =
        ATTRIBUTES.register("slow_time",
            () -> new RangedAttribute("attribute.yizmodqzk.slow_time", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 冰冻时长(tick) — 值域 ≥0。 */
    public static final RegistryObject<Attribute> FREEZE_TIME =
        ATTRIBUTES.register("freeze_time",
            () -> new RangedAttribute("attribute.yizmodqzk.freeze_time", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 感电时长(tick) — 值域 ≥0。 */
    public static final RegistryObject<Attribute> SHOCK_TIME =
        ATTRIBUTES.register("shock_time",
            () -> new RangedAttribute("attribute.yizmodqzk.shock_time", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 击飞时长(tick) — 值域 ≥0。 */
    public static final RegistryObject<Attribute> KNOCKBACK_TIME =
        ATTRIBUTES.register("knockback_time",
            () -> new RangedAttribute("attribute.yizmodqzk.knockback_time", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 感电范围(格) — 值域 ≥0。缺省 2.5。 */
    public static final RegistryObject<Attribute> SHOCK_RANGE =
        ATTRIBUTES.register("shock_range",
            () -> new RangedAttribute("attribute.yizmodqzk.shock_range", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 感电间隔(tick) — 值域 ≥0。缺省 10。 */
    public static final RegistryObject<Attribute> SHOCK_INTERVAL =
        ATTRIBUTES.register("shock_interval",
            () -> new RangedAttribute("attribute.yizmodqzk.shock_interval", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 眩晕伤害 — 值域 ≥0。缺省 2。 */
    public static final RegistryObject<Attribute> STUN_DAMAGE =
        ATTRIBUTES.register("stun_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.stun_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 减速伤害 — 值域 ≥0。 */
    public static final RegistryObject<Attribute> SLOW_DAMAGE =
        ATTRIBUTES.register("slow_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.slow_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 冰冻伤害 — 值域 ≥0。 */
    public static final RegistryObject<Attribute> FREEZE_DAMAGE =
        ATTRIBUTES.register("freeze_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.freeze_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 感电伤害 — 值域 ≥0。 */
    public static final RegistryObject<Attribute> SHOCK_DAMAGE =
        ATTRIBUTES.register("shock_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.shock_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 击飞伤害 — 值域 ≥0。 */
    public static final RegistryObject<Attribute> KNOCKBACK_DAMAGE =
        ATTRIBUTES.register("knockback_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.knockback_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    /** 感电数量 — 值域 ≥0。控制感电影响实体上限（伤害/闪电链）；0=未配置走默认。可累加。 */
    public static final RegistryObject<Attribute> SHOCK_COUNT =
        ATTRIBUTES.register("shock_count",
            () -> new RangedAttribute("attribute.yizmodqzk.shock_count", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

    //  === 组D 依赖下游系统 11 属性（2026-08-26 阶段7；消费在阶段7 下游系统）===

    /** 溅射半径(格) — 值域 ≥0。消费在 yizxian YizxianMod.onLivingDamage/executeSplash。 */
    public static final RegistryObject<Attribute> SPLASH_RADIUS =
        ATTRIBUTES.register("splash_radius",
            () -> new RangedAttribute("attribute.yizmodqzk.splash_radius", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 溅射伤害(%) — 值域 ≥0。以主目标为中心 AABB 范围伤害。 */
    public static final RegistryObject<Attribute> SPLASH_DAMAGE =
        ATTRIBUTES.register("splash_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.splash_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 溅射衰减(%) — 值域 ≥0。平滑二次曲线衰减 edgeMul=1-falloff/100。 */
    public static final RegistryObject<Attribute> SPLASH_FALLOFF =
        ATTRIBUTES.register("splash_falloff",
            () -> new RangedAttribute("attribute.yizmodqzk.splash_falloff", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 会心(锁定视觉范围,格) — 值域 ≥0。默认 12。 */
    public static final RegistryObject<Attribute> HUIXIN =
        ATTRIBUTES.register("huixin",
            () -> new RangedAttribute("attribute.yizmodqzk.huixin", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 克刚(锁定充能tick) — 值域 ≥0。默认 30。 */
    public static final RegistryObject<Attribute> KEGONG =
        ATTRIBUTES.register("kegong",
            () -> new RangedAttribute("attribute.yizmodqzk.kegong", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 多段跳次数 — 值域 ≥0。MultiJumpTracker.maxJumps 读取。 */
    public static final RegistryObject<Attribute> JUMP_COUNT =
        ATTRIBUTES.register("jump_count",
            () -> new RangedAttribute("attribute.yizmodqzk.jump_count", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 多段跳高度(格) — 值域 ≥0。默认 4。 */
    public static final RegistryObject<Attribute> JUMP_HEIGHT =
        ATTRIBUTES.register("jump_height",
            () -> new RangedAttribute("attribute.yizmodqzk.jump_height", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 最大召唤物数 — 值域 ≥0。默认 1。 */
    public static final RegistryObject<Attribute> MAX_MINIONS =
        ATTRIBUTES.register("max_minions",
            () -> new RangedAttribute("attribute.yizmodqzk.max_minions", 1.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 召唤伤害(%) — 值域 ≥0。召唤物伤害 ×(1+summon/100)。 */
    public static final RegistryObject<Attribute> SUMMON_DAMAGE =
        ATTRIBUTES.register("summon_damage",
            () -> new RangedAttribute("attribute.yizmodqzk.summon_damage", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 法力消耗 — 值域 ≥0。技能耗蓝显示/检测用。 */
    public static final RegistryObject<Attribute> MANA_COST =
        ATTRIBUTES.register("mana_cost",
            () -> new RangedAttribute("attribute.yizmodqzk.mana_cost", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));
    /** 每秒法力消耗 — 值域 ≥0。开关形技能持续耗蓝检测。 */
    public static final RegistryObject<Attribute> MANA_COST_PER_SEC =
        ATTRIBUTES.register("mana_cost_per_sec",
            () -> new RangedAttribute("attribute.yizmodqzk.mana_cost_per_sec", 0.0, 0.0, Double.MAX_VALUE).setSyncable(true));

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
