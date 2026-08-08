package net.minecraft.client.yiz.handler;

import net.minecraft.world.entity.LivingEntity;

/**
 * CDR 破无敌帧模块（1.20.1 占位版）。
 *
 * <p>完整版读攻击者 COOLDOWN_REDUCTION 属性等比缩减目标无敌帧。该属性不在本次移植的 17 属性内
 * （属技能/装备体系），骨架阶段空操作；后续移植 CDR 属性时补全。辖界者作为被动方，
 * 攻击者无 CDR 时 {@code getAttribute} 为 null → 值 0 → 本方法跳过，行为一致。</p>
 */
public final class InvulnBreakHandler {

    private InvulnBreakHandler() {}

    /** 根据攻击者 CDR 缩减目标当前无敌帧（骨架阶段空操作，待 CDR 属性移植）。 */
    public static void apply(LivingEntity attacker, LivingEntity target) {
        // TODO(属性体系): 移植 COOLDOWN_REDUCTION 属性后实现
    }
}
