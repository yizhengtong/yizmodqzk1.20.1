package net.minecraft.client.yiz.api;

/**
 * 破幻寻真的破除报告（只读 / 诊断用）。
 *
 * @param depth          当前生效深度（本属性固定全深度 = 4；0 = 未生效）
 * @param iframesZeroed  本次命中是否清零了无敌帧/受击硬直
 * @param rejectedSettled 是否因目标逻辑拒绝而改走 actuallyHurt 结算
 * @param creativeBypass 是否触发了“生存玩家被套创造无敌”的强制可伤
 * @param targetClass    目标实体类
 */
public record BreachReport(
    int depth,
    boolean iframesZeroed,
    boolean rejectedSettled,
    boolean creativeBypass,
    Class<?> targetClass
) {}
