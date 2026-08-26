package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.api.YizModQZKAPI;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

/**
 * 周期性属性效果应用器 — 消费需要每 tick 推动的 yizmodqzk 属性（1.21.1 移植版）。
 *
 * <p>由 {@code tizMod.onPlayerTick} 每 tick 调用 {@link #tick}。仿 {@link ManaTracker#tickRegen}
 * 范本。当前处理：
 * <ul>
 *   <li>{@link YizAttributes#LIFE_REGEN_RATE} + {@link YizAttributes#LIFE_REGEN_PCT}
 *       → 每 tick 生命回复（公式见 {@link #computeLifeRegen}），走 {@link YizModQZKAPI#healthRegen}
 *       的 Delta 通道，满血不回、不受禁疗拦截</li>
 * </ul>
 *
 * <p>注：MOVE_SPEED/MAX_RUN_SPEED/AIR_SPEED 的周期应用改由 {@code PlayerMovementMixin}
 * 在 getSpeed()/travel() 注入点处理（语义对齐 yizxian，非简单写回原版属性）。</p>
 */
public final class AttributeEffectTicker {

    private AttributeEffectTicker() {}

    /**
     * 每 tick 应用周期性属性效果。
     * <p>仅在服务端推动（healthRegen 走 Delta 通道会自动同步客户端血量）。</p>
     */
    public static void tick(LivingEntity entity) {
        if (entity.level().isClientSide()) return;
        if (entity.isDeadOrDying()) return;
        applyLifeRegen(entity);
    }

    /**
     * 生命恢复公式（每 tick）：
     * <pre>regen = LIFE_REGEN_RATE × 0.05 + maxHealth × LIFE_REGEN_PCT × 0.0005</pre>
     * 对齐 yizxian {@code applyAccessoryRegen} 语义。满血时 healthRegen 内部直接 return。
     */
    private static void applyLifeRegen(LivingEntity entity) {
        AttributeInstance rateInst = entity.getAttribute(YizAttributes.LIFE_REGEN_RATE.get());
        AttributeInstance pctInst = entity.getAttribute(YizAttributes.LIFE_REGEN_PCT.get());
        double rate = rateInst != null ? rateInst.getValue() : 0;
        double pct = pctInst != null ? pctInst.getValue() : 0;
        if (rate <= 0 && pct <= 0) return;

        float regen = 0f;
        if (rate > 0) regen += (float) (rate * 0.05);
        if (pct > 0) regen += entity.getMaxHealth() * (float) pct * 0.0005f;
        if (regen > 0) YizModQZKAPI.healthRegen(entity, regen);
    }
}
