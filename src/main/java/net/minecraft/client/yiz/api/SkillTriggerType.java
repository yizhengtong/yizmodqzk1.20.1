package net.minecraft.client.yiz.api;

/**
 * 强化标签的生效时机（区分「开启技能」与「触发技能」两个不同概念）。
 *
 * <pre>
 * ACTIVATE — 技能「开启」时：玩家按键激活那一刻。所有主动技能都有，一次施法触发 1 次。
 *            例：奔雷疾突进那一刻、雷鸣电甲按下开启那一刻。
 *            适用强化：双重施法、减冷却、释放时附加状态等。
 * TRIGGER  — 技能「触发」时：运行中实际造成一次效果（伤害/治疗/施加状态）的时刻。
 *            一次施法触发 0 或 N 次。纯位移技能 = 0 次（奔雷疾）；周期伤害型 = N 次（雷鸣电甲每次链电）。
 *            适用强化：触发时额外伤害、命中时施加状态、击退等。
 * </pre>
 *
 * <p>调度器 {@code EnhanceTagRegistry.executeActiveTags} 按此枚举过滤标签：
 * 标签注册时声明自己在哪个时机生效，技能在对应入口调用分发。</p>
 *
 * @see net.minecraft.client.yiz.api.ISkillItem#onActivate
 * @see net.minecraft.client.yiz.api.ISkillItem#onTrigger
 */
public enum SkillTriggerType {

    ACTIVATE,
    TRIGGER
}
