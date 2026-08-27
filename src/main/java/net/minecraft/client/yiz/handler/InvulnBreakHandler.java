package net.minecraft.client.yiz.handler;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

/**
 * CDR（攻击速度加成）破无敌帧模块（1.21.1 移植补全）。
 *
 * <p>职责：读取攻击者的 COOLDOWN_REDUCTION 属性值，
 * 等比缩减目标的 {@code invulnerableTime}。</p>
 *
 * <p>调用位置：{@code LivingEntityMixin#onHurtPre}（hurt HEAD），
 * 在 vanilla 无敌帧检查之前执行。</p>
 *
 * <h3>公式</h3>
 * invulnerableTime = round( invulnerableTime * ( 1 - cdr / 100 ) )
 */
public final class InvulnBreakHandler {

    private InvulnBreakHandler() {}

    /**
     * 根据攻击者 CDR 缩减目标当前无敌帧。
     *
     * <p>CDR 只注册给 {@code EntityType.PLAYER}（见 tizMod 属性注册段）。
     * 非玩家来源（怪/投射物/技能实体）读到 0 属正常，直接跳过。</p>
     *
     * @param attacker 伤害来源实体（需为带 CDR 的玩家才有意义）
     * @param target   受伤目标
     */
    public static void apply(LivingEntity attacker, LivingEntity target) {
        if (attacker == null || target == null) return;
        try {
            // 显式取 AttributeInstance：attacker 非玩家/未挂 CDR 时为 null → 值视为 0，直接跳过。
            AttributeInstance inst = attacker.getAttribute(YizAttributes.COOLDOWN_REDUCTION.get());
            double value = inst != null ? inst.getValue() : 0.0;
            if (value <= 0) return;

            // hurt HEAD 阶段首击/Player.attack 已清零 invuln → old 多为 0，缩放无意义。
            int old = target.invulnerableTime;
            if (old <= 0) return;

            double scale = 1.0 - Math.min(value, 100.0) / 100.0;
            target.invulnerableTime = Math.max(0, (int) (old * scale));
        } catch (Throwable ignored) {}
    }
}
