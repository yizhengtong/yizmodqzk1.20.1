package net.minecraft.client.yiz.tool.skill;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.server.level.ServerPlayer;

/**
 * 技能周期间隔读取辅助类（与 {@link SkillRanges} 同构，区别是间隔为「缩短」语义）。
 *
 * <p>三层结构：</p>
 * <pre>
 *   最终间隔(purpose) = (base + offset[purpose]) × (1 - SKILL_INTERVAL/100)
 * </pre>
 * <ul>
 *   <li><b>base 基础值</b> — 技能代码常量（如雷鸣电甲链电 5tick、护盾 4tick、雷震千里击退 2tick）。</li>
 *   <li><b>offset 定向偏移</b> — 按用途隔离的 tick 增量，带过期时间戳。正=周期变长，负=周期变短。</li>
 *   <li><b>SKILL_INTERVAL 加速率</b> — 属性，全局缩短所有用途的周期。例 20 = 所有间隔 ×0.8（触发更频繁）。</li>
 * </ul>
 *
 * <p>用途名约定：{@code "damage"} / {@code "shield"} / {@code "knockback"} 等，与 {@link SkillRanges} 一致。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 雷鸣电甲链电间隔（base=5tick），套上加速率后可能是 4tick
 * int interval = (int) Math.max(1, SkillIntervals.get(player, 5, "damage"));
 * if (elapsed % interval == 0) { /* 触发链电 *\/ }
 * }</pre>
 */
public final class SkillIntervals {

    private SkillIntervals() {}

    private static final String OFFSET_PREFIX = "yiz:intoff_";
    private static final String EXPIRE_PREFIX = "yiz:intexp_";

    /**
     * 读取某用途的最终间隔（tick）。
     *
     * @param player  施法玩家（服务端）
     * @param base    该用途的基础间隔（技能代码常量，tick）
     * @param purpose 用途名
     * @return (base + offset) × (1 - SKILL_INTERVAL/100)，下限 0
     */
    public static double get(ServerPlayer player, double base, String purpose) {
        double offset = readOffset(player, purpose);
        long expire = readExpire(player, purpose);
        if (expire > 0 && player.level().getGameTime() > expire) {
            clearOffset(player, purpose);
            offset = 0;
        }
        // TODO(1.20.1-port): 依赖 attribute/YizAttributes.SKILL_INTERVAL 注册（1.21.1 id "skill_interval"）。
        double accel = player.getAttributeValue(YizAttributes.SKILL_INTERVAL.get());
        return Math.max(0, (base + offset) * (1 - accel / 100.0));
    }

    /** 给某用途添加间隔偏移（叠加）。durationTicks≤0 表示永久。 */
    public static void addOffset(ServerPlayer player, String purpose, double amount, long durationTicks) {
        var pd = player.getPersistentData();
        double current = readOffset(player, purpose);
        pd.putDouble(OFFSET_PREFIX + purpose, current + amount);
        if (durationTicks > 0) {
            pd.putLong(EXPIRE_PREFIX + purpose, player.level().getGameTime() + durationTicks);
        }
    }

    /** 直接设置某用途的间隔偏移（覆盖）。 */
    public static void setOffset(ServerPlayer player, String purpose, double amount, long durationTicks) {
        var pd = player.getPersistentData();
        pd.putDouble(OFFSET_PREFIX + purpose, amount);
        if (durationTicks > 0) {
            pd.putLong(EXPIRE_PREFIX + purpose, player.level().getGameTime() + durationTicks);
        } else {
            pd.remove(EXPIRE_PREFIX + purpose);
        }
    }

    /** 清除某用途的全部间隔偏移。 */
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
