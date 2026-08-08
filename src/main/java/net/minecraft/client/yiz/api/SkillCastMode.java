package net.minecraft.client.yiz.api;

/**
 * 技能按键驱动模式。
 *
 * <pre>
 * INSTANT    — 按一下释放一次，有冷却
 * CONTINUOUS — 按住持续连发，有发射间隔
 * TOGGLE     — 按一下开启，再按关闭（开关型）
 * CHARGE     — 按住蓄力，松手释放（蓄力等级随按住时间增长）
 * </pre>
 */
public enum SkillCastMode {

    INSTANT,
    CONTINUOUS,
    TOGGLE,
    CHARGE
}
