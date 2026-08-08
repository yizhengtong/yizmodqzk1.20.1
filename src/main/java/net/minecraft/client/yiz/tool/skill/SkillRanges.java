package net.minecraft.client.yiz.tool.skill;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.server.level.ServerPlayer;

/**
 * 技能效果范围读取辅助类（丙方案 + 解法 A）。
 *
 * <p>三层结构，支持「用途区分」和「实时扩展」：</p>
 * <pre>
 *   最终范围(purpose) = (base + offset[purpose]) × (1 + SKILL_RANGE/100)
 * </pre>
 * <ul>
 *   <li><b>base 基础值</b> — 技能代码常量，作为参数传入。覆盖「技能成长永久扩大」（升级改 base 公式）。</li>
 *   <li><b>offset 定向偏移</b> — 存玩家 PersistentData，按用途隔离，带过期时间戳。
 *       覆盖「标签临时扩大 N 秒」和「条件动态扩大」。惰性清理：读取时若已过期则自动清除。</li>
 *   <li><b>SKILL_RANGE 倍率</b> — 属性，全局放大所有用途。覆盖「强化槽装备永久倍率」。</li>
 * </ul>
 *
 * <p>用途名（purpose）是自由字符串，约定俗成：{@code "damage"} / {@code "heal"} /
 * {@code "knockback"} / {@code "chain"} / {@code "shield"} / {@code "buff"} 等。
 * 同一玩家不同用途的偏移互不影响。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 技能 onTick 里读伤害范围（base=6.0 是技能写死的常量）
 * double range = SkillRanges.get(player, 6.0, "damage");
 *
 * // 强化标签：给伤害范围临时 +3.0，持续 5 秒(100tick)
 * SkillRanges.addOffset(player, "damage", 3.0, 100);
 * }</pre>
 */
public final class SkillRanges {

    private SkillRanges() {}

    /** PersistentData key 前缀 */
    private static final String OFFSET_PREFIX = "yiz:rangeoff_";
    private static final String EXPIRE_PREFIX = "yiz:rangeexp_";

    /**
     * 读取某用途的最终范围。
     *
     * @param player  施法玩家（服务端）
     * @param base    该用途的基础值（技能代码常量）
     * @param purpose 用途名（damage/heal/knockback/...）
     * @return (base + offset) × (1 + SKILL_RANGE/100)，下限 0
     */
    public static double get(ServerPlayer player, double base, String purpose) {
        double offset = readOffset(player, purpose);
        long expire = readExpire(player, purpose);
        // 惰性清理：永久偏移(expire<=0)不清理；临时偏移过期则清
        if (expire > 0 && player.level().getGameTime() > expire) {
            clearOffset(player, purpose);
            offset = 0;
        }
        // TODO(1.20.1-port): 依赖 attribute/YizAttributes.SKILL_RANGE 注册（1.21.1 id "skill_range"）。
        double mult = player.getAttributeValue(YizAttributes.SKILL_RANGE.get());
        return Math.max(0, (base + offset) * (1 + mult / 100.0));
    }

    /**
     * 给某用途添加定向偏移（叠加到现有偏移上）。
     *
     * @param player         施法玩家
     * @param purpose        用途名
     * @param amount         偏移量（格，正=扩大，负=缩小）
     * @param durationTicks  持续 tick 数；0 或负数 = 永久（需手动 clearOffset 清除）
     */
    public static void addOffset(ServerPlayer player, String purpose, double amount, long durationTicks) {
        var pd = player.getPersistentData();
        double current = readOffset(player, purpose);
        pd.putDouble(OFFSET_PREFIX + purpose, current + amount);
        if (durationTicks > 0) {
            // 临时偏移：记录过期时间。多次叠加取最近一次的过期时间。
            pd.putLong(EXPIRE_PREFIX + purpose, player.level().getGameTime() + durationTicks);
        }
    }

    /**
     * 直接设置某用途的偏移（覆盖，非叠加）。
     */
    public static void setOffset(ServerPlayer player, String purpose, double amount, long durationTicks) {
        var pd = player.getPersistentData();
        pd.putDouble(OFFSET_PREFIX + purpose, amount);
        if (durationTicks > 0) {
            pd.putLong(EXPIRE_PREFIX + purpose, player.level().getGameTime() + durationTicks);
        } else {
            pd.remove(EXPIRE_PREFIX + purpose);
        }
    }

    /** 清除某用途的全部偏移（临时/永久一并清除）。 */
    public static void clearOffset(ServerPlayer player, String purpose) {
        var pd = player.getPersistentData();
        pd.remove(OFFSET_PREFIX + purpose);
        pd.remove(EXPIRE_PREFIX + purpose);
    }

    private static double readOffset(ServerPlayer player, String purpose) {
        return player.getPersistentData().getDouble(OFFSET_PREFIX + purpose);
    }

    private static long readExpire(ServerPlayer player, String purpose) {
        return player.getPersistentData().getLong(EXPIRE_PREFIX + purpose);
    }
}
