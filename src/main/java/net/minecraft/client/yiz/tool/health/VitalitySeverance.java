package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.api.VitalitySeveranceAttributeRegistry;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.LivingEntity;

/**
 * 绝妄生机（原禁疗）统一施加入口 —— 攻方属性驱动的唯一入口。
 *
 * <p>聚合全部「攻击者属性 → 目标绝妄生机」的施加源，攻击命中处只需调一次
 * {@link #apply(attacker, target)}：</p>
 * <ol>
 *   <li>注册表聚合绝妄生机（{@link VitalitySeveranceAttributeRegistry} 注册的百分比/固定属性）</li>
 *   <li>绝妄生机率属性（VITALITY_SEVERANCE_RATE）→ 目标百分比禁疗（保留目标已有固定值）</li>
 *   <li>绝妄生机时间属性（VITALITY_SEVERANCE_TIME）→ 目标完全禁疗 N 秒（连续攻击刷新）</li>
 * </ol>
 *
 * <p>原来分散在 {@code AttackInterceptorMixin} 与 {@code LivingEntityMixin.onHurtReturn}
 * 的施加逻辑收敛到这里（幂等：Config.set / addTempBan 都是覆盖语义，重复调用无害）。</p>
 *
 * <p>1.20.1 移植版：
 * <ul>
 *   <li>属性引用 {@link YizAttributes} 字段为 {@code RegistryObject<Attribute>}，访问加 {@code .get()}。</li>
 *   <li>{@code VitalitySeveranceAttributeRegistry} 尚未移植（1.21.1 中位于 api 包，约 98 行），见下方 TODO。</li>
 *   <li>{@code VitalitySeveranceHandler.addTempBan} 尚未移植（目标 handler 目前仅有叠加式
 *       {@code addStackingBan}），见下方 TODO。</li>
 * </ul></p>
 */
public final class VitalitySeverance {

    private VitalitySeverance() {}

    /** 统一施加入口：读攻击者全部绝妄生机来源，对目标施加。 */
    public static void apply(LivingEntity attacker, LivingEntity target) {
        if (attacker == null || target == null) return;
        if (attacker.level().isClientSide()) return;

        // 1. 注册表聚合绝妄生机（百分比 + 固定值）
        // TODO(1.20.1-port): 需先移植 api/VitalitySeveranceAttributeRegistry
        // （Holder<Attribute> → Attribute 键，1.20.1 属性为 RegistryObject<Attribute>）。
        float percent = VitalitySeveranceAttributeRegistry.getPercentTotal(attacker);
        float fixed = VitalitySeveranceAttributeRegistry.getFixedTotal(attacker);
        if (percent > 0 || fixed > 0) {
            VitalitySeveranceConfig.set(target, percent, fixed);
        }

        // 2. 绝妄生机率：目标每次治疗被削减攻击者率%（保留目标已有固定禁疗值）
        var arInst = attacker.getAttribute(YizAttributes.VITALITY_SEVERANCE_RATE.get());
        if (arInst != null && arInst.getValue() > 0) {
            var cfg = VitalitySeveranceConfig.get(target);
            float existingFixed = cfg != null ? cfg.fixedAmount() : 0;
            VitalitySeveranceConfig.set(target, (float) arInst.getValue(), existingFixed);
        }

        // 3. 绝妄生机时间：目标完全禁疗 N 秒（秒 → tick，连续攻击 put 覆盖刷新时长）
        var atInst = attacker.getAttribute(YizAttributes.VITALITY_SEVERANCE_TIME.get());
        if (atInst != null && atInst.getValue() > 0) {
            // TODO(1.20.1-port): VitalitySeveranceHandler 尚无 addTempBan(LivingEntity, float, long)。
            // 语义 = 100% 禁疗 N tick；可用现成 addStackingBan(target, 1.0f, ticks) 近似，
            // 或后续按 1.21.1 补 addTempBan。
            VitalitySeveranceHandler.addTempBan(target, 1.0f, (long) (atInst.getValue() * 20.0));
        }
    }
}
