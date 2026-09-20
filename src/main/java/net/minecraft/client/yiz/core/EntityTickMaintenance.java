package net.minecraft.client.yiz.core;

import net.minecraft.client.yiz.tool.health.AttributeEffectTicker;
import net.minecraft.client.yiz.tool.health.ConductionDamageLimiter;
import net.minecraft.client.yiz.tool.health.HealthModificationScheduler;
import net.minecraft.client.yiz.tool.health.HealthWriteGuard;
import net.minecraft.client.yiz.tool.health.SecureHealthClosure;
import net.minecraft.client.yiz.tool.health.VitalitySeveranceHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 实体"每 tick 维护"清单（原样抽取自 {@code LivingEntityMixin} 的 tick 尾部注入）。
 *
 * <p>抽出来的唯一目的：<b>击飞期间目标自身 tick 被停掉，这份维护必须由 {@code LaunchController} 在驱动弹道时补跑</b>。
 * 否则会出现一类很难查的问题——血量相关设施全部失效：</p>
 * <ul>
 *   <li>延迟任务调度器不跑（{@code HealthModificationScheduler}：击杀兜底、门控探测、压制任务）；</li>
 *   <li>传导限伤状态机不推进（{@code ConductionDamageLimiter} 的 CD/上限判定）；</li>
 *   <li>写基线/禁疗/回血等 enforce 类不执行；</li>
 * </ul>
 * <p>实测症状：被击飞的目标"只有第 1 次攻击造成伤害，后续只击飞不掉血"。</p>
 *
 * <p>调用点：① 实体自身 tick 尾部（{@code forcePeriodic=false}，周期项按 tickCount%10）；
 * ② 击飞飞行期驱动（{@code forcePeriodic=true}——此时 tickCount 已冻结，周期项只能每 tick 跑，飞行期很短可接受）。</p>
 */
public final class EntityTickMaintenance {

    private EntityTickMaintenance() {}

    /**
     * @param forcePeriodic true = 无视 tickCount%10 直接跑周期项（击飞飞行期用）
     */
    public static void tick(LivingEntity entity, boolean forcePeriodic) {
        if (entity == null || entity.level().isClientSide()) return;

        net.minecraft.client.yiz.handler.AttackInvulnerabilityTracker.onTick(entity, entity.level().getGameTime());
        StatusEffectDispatcher.tickControlTimers(entity);

        HealthModificationScheduler.tick(entity);
        ConductionDamageLimiter.tick(entity);
        SecureHealthClosure.tick(entity);
        // 实体属性回血（LIFE_REGEN_RATE/PCT 每 tick；玩家已在 tizMod.onPlayerTick 处理，避免双 tick）
        if (!(entity instanceof Player)) {
            AttributeEffectTicker.tick(entity);
        }

        if (forcePeriodic || entity.tickCount % 10 == 0) {
            VitalitySeveranceHandler.enforceTick(entity);
            VitalitySeveranceHandler.enforceFieldTick(entity);
            HealthWriteGuard.enforce(entity);
        }
    }
}
