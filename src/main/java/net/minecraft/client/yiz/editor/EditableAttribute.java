package net.minecraft.client.yiz.editor;

import net.minecraft.client.yiz.tool.attribute.ItemAttributeHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * "可设属性"数据源：属性编辑台列表遍历此集合显示（1.21.1 移植版）。
 *
 * <p>1.20.1 差异：1.21.1 用 ResourceLocation+Holder 定位属性、DataComponents 存储物品 modifier；
 * 1.20.1 改用 {@link Attribute}（ForgeRegistries.ATTRIBUTES）+ {@link ItemAttributeHandler#setYizModifier}
 * （addAttributeModifier 幂等）。interaction_range/sweep_ratio 依赖 1.20.2+/NeoForge 专属属性，空实现记 gap。</p>
 *
 * <p>单位语义：% 百分比 1=1%；点 固定点；格 方块距离；tick 游戏刻；次 次数。</p>
 */
public record EditableAttribute(
    String id,
    String displayName,
    boolean unimplemented,
    String unit,
    BiConsumer<ItemStack, Double> setter,
    Function<ItemStack, Double> getter,
    Function<Player, Double> playerReader
) {

    // ═══════════════════════════════════════════════════════════
    //  内置列表
    // ═══════════════════════════════════════════════════════════

    private static final List<EditableAttribute> BUILTIN = List.of(
        // ── 原版 ────────────────────────────────────────────
        vanilla("generic.attack_damage",    "攻击力",     false, ""),
        vanilla("generic.attack_speed",     "攻击速度",   false, ""),
        vanilla("generic.attack_knockback", "攻击击退",   false, ""),
        vanilla("generic.armor",            "护甲值",     false, ""),
        vanilla("generic.armor_toughness",  "盔甲韧性",   false, ""),
        vanilla("generic.max_health",       "最大生命",   false, ""),
        vanilla("generic.knockback_resistance", "击退抗性", false, ""),
        vanilla("generic.luck",             "幸运",       false, ""),
        vanilla("generic.movement_speed",   "移动速度",   false, ""),

        // ── 特殊路径 ──────────────────────────────────────
        attr("max_durability", "耐久值", false, "点",
            (s, v) -> ItemAttributeHandler.addMaxDurability(s, v.intValue()),
            s -> (double) ItemAttributeHandler.getMaxDurability(s),
            p -> 0.0),
        // interaction_range/sweep_ratio 依赖 1.20.2+ ENTITY_INTERACTION_RANGE / NeoForge sweeping 属性，空实现记 gap
        attr("interaction_range", "交互距离", false, "格",
            (s, v) -> {}, s -> 0.0, p -> 0.0),
        attr("sweep_ratio", "横扫比例", false, "%",
            (s, v) -> {}, s -> 0.0, p -> 0.0),

        // ── 库属性（百分比类 1=1%）────────────────────────
        yiz("crit_rate",            "暴击率",       false, "%"),
        yiz("crit_damage",          "暴击伤害",     false, "%"),
        yiz("life_steal",           "全能吸血",     false, "%"),
        yiz("splash_radius",        "溅射半径",     false, "格"),
        yiz("splash_damage",        "溅射伤害",     false, "%"),
        yiz("splash_falloff",       "溅射衰减",     false, "%"),
        yiz("huixin",               "会心",         false, "格"),
        yiz("kegong",               "渴攻",         false, "tick"),
        yiz("cooldown_reduction",   "攻击速度加成", false, "%"),
        yiz("auto_attack",          "自动攻击",     false, ""),

        // 库 — 点数/次数类
        yiz("attack_strength",   "攻击加成",     false, "%"),
        yiz("spell_defense",       "魔法抗性",     false, "点"),
        yiz("spell_power",         "法术强度",     false, "点"),
        yiz("cooldown_value",      "技能冷却值",   false, "tick"),
        yiz("max_charges",         "最大充能数",   false, "次"),
        yiz("armor",               "护甲抗性",     false, "点"),
        yiz("shield_value",        "护盾值",       false, "点"),
        yiz("damage_block",        "格挡",         false, "点"),
        // 蓝条系统
        yiz("max_mana",            "最大法力值",   false, "点"),
        yiz("mana_regen",          "定量法力回复", false, ""),
        yiz("mana_regen_pct",      "每秒百分比法力恢复", false, "%"),
        yiz("mana_cost_reduction", "法力值消耗降低", false, "点"),
        // generic_damage: 用户输入 10 → 存 0.1 → 实际 +10%
        yiz("generic_damage",      "全伤害",       false, "%"),
        yiz("damage_reduction",    "伤害减免",     false, "%"),
        yiz("counter_rate",        "反击率",       false, "%"),
        yiz("counter_value",       "反击值",       false, "%"),
        yiz("combo_rate",          "连击",         false, "%"),
        yiz("combo_value",         "连击倍率",     false, "%"),
        yiz("combo_count",         "连击次数",     false, "次"),
        yiz("undying",             "不死",         false, "次"),

        // ── 迁移自 EffectTag 的原生属性 ────────────────
        yiz("move_speed",           "移动速度",     false, ""),
        yiz("max_run_speed",        "最大奔跑速度", false, ""),
        yiz("jump_strength",        "跳跃力度",     false, ""),
        yiz("air_speed",            "空中移速",     false, ""),
        yiz("jump_count",           "跳跃次数",     false, "次"),
        yiz("jump_height",          "跳跃高度",     false, "格"),
        yiz("fall_safe",            "跌落保护",     false, "格"),
        yiz("fall_reduce",          "跌落减免",     false, "格"),
        yiz("dodge_chance",         "闪避几率",     false, "%"),
        yiz("invincibility_mult",   "无敌帧倍率",   false, "tick"),
        yiz("lava_immune_time",     "熔岩免疫时间", false, "tick"),
        yiz("lava_damage_reduction","熔岩减伤",     false, "%"),
        yiz("life_regen_rate",      "定量生命回复", false, "点/tick"),
        yiz("life_regen_pct",       "百分比生命回复", false, "%"),
        yiz("melee_damage",         "近战伤害",     false, ""),
        yiz("ranged_damage",        "远程伤害",     false, ""),
        yiz("magic_damage",         "法术加成",     false, "%"),
        yiz("summon_damage",        "召唤伤害",     false, "%"),
        yiz("armor_penetration",    "护甲穿透%",    false, "%"),
        yiz("armor_penetration_flat","护甲穿透固定", false, "点"),
        yiz("attack_range",         "交互距离",     false, "格"),
        yiz("jump_speed",           "步高",         false, "格"),
        yiz("max_minions",          "最大仆从数",   false, "次"),
        yiz("max_sentries",         "最大哨兵数",   false, "次"),
        yiz("water_breath_time",    "水下呼吸时间", false, "秒"),

        // 触发器（次数）/ 布尔型
        yiz("projectile_reflection","投射物反弹",   false, "格"),
        yiz("no_collision",         "无碰撞",       false, ""),
        yiz("knockback_immunity",   "击退免疫",     false, ""),
        yiz("projectile_immunity",  "投射物免疫",   false, ""),

        // 状态效果 — 攻方
        yiz("stun_attack",          "眩晕(攻)",     false, "%"),
        yiz("slow_attack",          "减速(攻)",     false, "%"),
        yiz("freeze_attack",        "冰冻(攻)",     false, "%"),
        yiz("shock_attack",         "感电(攻)",     false, "%"),
        yiz("knockback_attack",     "击飞(攻)",     false, "%"),
        // 状态效果 — 防方
        yiz("stun_defense",         "眩晕(防)",     false, "%"),
        yiz("slow_defense",         "减速(防)",     false, "%"),
        yiz("freeze_defense",       "冰冻(防)",     false, "%"),
        yiz("shock_defense",        "感电(防)",     false, "%"),
        yiz("knockback_defense",    "击飞(防)",     false, "%"),
        // 状态效果共享 — 时间/范围
        yiz("stun_time",            "眩晕时间",     false, "tick"),
        yiz("slow_time",            "减速时间",     false, "tick"),
        yiz("freeze_time",          "冰冻时间",     false, "tick"),
        yiz("shock_time",           "感电时间",     false, "tick"),
        yiz("shock_range",          "感电范围",     false, "格"),
        yiz("shock_interval",       "感电间隔",     false, "tick"),
        yiz("knockback_time",       "击飞时间",     false, "tick"),
        // 状态效果共享 — 伤害
        yiz("stun_damage",          "眩晕伤害",     false, "点"),
        yiz("slow_damage",          "减速伤害",     false, "点"),
        yiz("freeze_damage",        "冰冻伤害",     false, "点"),
        yiz("shock_damage",         "感电伤害",     false, "点"),
        yiz("knockback_damage",     "击飞伤害",     false, "点"),
        yiz("shock_count",          "感电数量",     false, "个"),

        // 挖掘属性
        yiz("mining_level",             "挖掘等级",     false, "点"),
        yiz("mining_pickaxe",           "挖掘类：镐",   false, ""),
        yiz("mining_axe",               "挖掘类：斧",   false, ""),
        yiz("mining_shovel",            "挖掘类：铲",   false, ""),
        yiz("mining_all",               "挖掘类：全",   false, ""),
        yiz("mining_penalty_immunity",  "免疫挖掘惩罚", false, ""),
        yiz("mining_efficiency",        "挖掘效率",     false, "%"),

        // 绝妄生机 + 特殊伤害
        yiz("vitality_severance_rate",   "绝妄生机率",   false, "%"),
        yiz("vitality_severance_time",   "绝妄生机时间", false, "秒"),
        yiz("long_short",                "涨跌多空",     false, "点"),
        yiz("dream_percent",             "灭在多空",     false, "%")
    );

    // ═══════════════════════════════════════════════════════════
    //  扩展注册
    // ═══════════════════════════════════════════════════════════

    private static final List<EditableAttribute> EXTRA = new ArrayList<>();

    public static void registerExtra(EditableAttribute attr) { EXTRA.add(attr); }

    public static List<EditableAttribute> getAll() {
        if (EXTRA.isEmpty()) return BUILTIN;
        List<EditableAttribute> merged = new ArrayList<>(BUILTIN.size() + EXTRA.size());
        merged.addAll(BUILTIN);
        merged.addAll(EXTRA);
        return Collections.unmodifiableList(merged);
    }

    // ═══════════════════════════════════════════════════════════
    //  工厂方法
    // ═══════════════════════════════════════════════════════════

    private static EditableAttribute vanilla(String id, String name, boolean u, String unit) {
        Attribute attr = getAttr(new ResourceLocation("minecraft", id));
        // 方案 A：NBT 存属性（tooltip 不显示槽位行）；playerReader 读实体聚合后的属性
        return new EditableAttribute(id, name, u, unit,
            (s, v) -> net.minecraft.client.yiz.tool.attribute.NbtAttributeHelper.set(s, id, v),
            s -> net.minecraft.client.yiz.tool.attribute.NbtAttributeHelper.get(s, id),
            p -> playerAttr(p, attr));
    }

    private static EditableAttribute yiz(String attrId, String name, boolean u, String unit) {
        Attribute attr = getAttr(new ResourceLocation("yizmodqzk", attrId));
        // 方案 A：NBT 存属性；playerReader 读实体聚合后的属性
        return new EditableAttribute(attrId, name, u, unit,
            (s, v) -> net.minecraft.client.yiz.tool.attribute.NbtAttributeHelper.set(s, attrId, v),
            s -> net.minecraft.client.yiz.tool.attribute.NbtAttributeHelper.get(s, attrId),
            p -> playerAttr(p, attr));
    }

    private static EditableAttribute attr(String id, String name, boolean u, String unit,
            BiConsumer<ItemStack, Double> setter, Function<ItemStack, Double> getter,
            Function<Player, Double> playerReader) {
        return new EditableAttribute(id, name, u, unit, setter, getter, playerReader);
    }

    // ═══════════════════════════════════════════════════════════
    //  读写（1.20.1：Attribute 定位 + ItemAttributeHandler modifier）
    // ═══════════════════════════════════════════════════════════

    /** 从 ForgeRegistries 解析属性（minecraft:/yizmodqzk: 命名空间）。 */
    private static Attribute getAttr(ResourceLocation loc) {
        if (loc == null) return null;
        return ForgeRegistries.ATTRIBUTES.getValue(loc);
    }

    private static void setAttr(ItemStack stack, Attribute attr, String idKey, double value) {
        if (attr == null) return;
        ItemAttributeHandler.setYizModifier(stack, attr, idKey, value);
    }

    private static double sumAttr(ItemStack stack, Attribute attr) {
        return attr != null ? ItemAttributeHandler.sumVanillaModifier(stack, attr) : 0;
    }

    private static double playerAttr(Player player, Attribute attr) {
        if (attr == null) return 0;
        var inst = player.getAttribute(attr);
        return inst != null ? inst.getValue() : 0;
    }

    // ═══════════════════════════════════════════════════════════
    //  显示
    // ═══════════════════════════════════════════════════════════

    public String listLabel(double currentValue) {
        String suffix = unimplemented ? "（待实现）" : "";
        if (Math.abs(currentValue) < 0.0001) return displayName + suffix;
        return displayName + " " + formatWithUnit(currentValue) + suffix;
    }

    public String hudLabel(double playerValue) {
        if (Math.abs(playerValue) < 0.0001) return null;
        return displayName + " " + formatWithUnit(playerValue);
    }

    private String formatWithUnit(double v) {
        return switch (unit) {
            case "%"  -> formatValue(v) + "%";
            case "点" -> formatValue(v) + "点";
            case "格" -> formatValue(v) + "格";
            case "tick" -> formatValue(v) + " tick";
            case "次" -> formatValue(v) + "次";
            default -> formatValue(v);
        };
    }

    private static String formatValue(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.format("%.1f", v);
    }
}
