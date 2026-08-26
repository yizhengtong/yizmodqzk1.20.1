package net.minecraft.client.yiz.handler;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端锁定状态机 + 交互距离管理（1.21.1 移植版，仅服务端核心）。
 *
 * <p>从玩家实体属性读取会心(HUIXIN)/渴攻(KEGONG)，服务端独立追踪充能进度。
 * 1.20.1 差异（见 port-gap-list #7/#13）：
 * <ul>
 *   <li>EntityLockAPI（客户端目标框同步）→ 本类静态 {@link #getLockProgress} 供客户端查询</li>
 *   <li>充能完成挂 ENTITY_INTERACTION_RANGE 距离修饰符（1.20.2+ 属性）→ 跳过，距离效果待 PlayerRange 扩展</li>
 *   <li>LockOnProvider 目标框渲染（TargetFrameProvider）→ 未移植</li>
 * </ul>
 * HUIXIN/KEGONG 属性驱动充能状态 + 攻击重置仍生效。</p>
 */
public final class LockOnHandler {

    private static final double DEFAULT_HUIXIN = 12.0;
    private static final double DEFAULT_KEGONG = 30.0;
    private static final double CONE_DOT = 0.866; // cos(30°) ≈ 0.866

    private LockOnHandler() {}

    // ═══════════════════════════════════════════════════════════
    //  状态存储
    // ═══════════════════════════════════════════════════════════

    private record LockState(UUID targetUuid, int timer) {}
    private static final Map<UUID, LockState> STATES = new ConcurrentHashMap<>();

    /** 客户端查询：最近锁定进度 [0,1]（-1=未锁定）。供目标框/UI 渲染。 */
    private static final Map<UUID, Float> PROGRESS = new ConcurrentHashMap<>();

    /** 客户端查询锁定进度 [0,1]（-1 = 无锁定）。 */
    public static float getLockProgress(Player player) {
        return PROGRESS.getOrDefault(player.getUUID(), -1f);
    }

    // ═══════════════════════════════════════════════════════════
    //  Tick: 充能更新（由 tizMod 注册）
    // ═══════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (player.isDeadOrDying()) return;

        // 读取属性，应用默认互补
        double rawHuixin = readAttr(player, YizAttributes.HUIXIN);
        double rawKegong = readAttr(player, YizAttributes.KEGONG);
        if (rawHuixin <= 0 && rawKegong <= 0) {
            reset(player);
            return;
        }
        double range = rawHuixin > 0 ? rawHuixin : DEFAULT_HUIXIN;
        int chargeTicks = rawKegong > 0 ? (int) rawKegong : (int) DEFAULT_KEGONG;

        // 找目标
        LivingEntity target = findTarget(player, range);
        UUID puid = player.getUUID();
        LockState state = STATES.get(puid);

        if (target == null || !target.isAlive()) {
            // 移开视线只清锁状态
            if (state != null) clearLockState(player);
            return;
        }

        UUID tuid = target.getUUID();
        int timer;
        if (state != null && state.targetUuid.equals(tuid)) {
            timer = Math.min(state.timer + 1, chargeTicks);
        } else {
            timer = 1; // 换目标重新充能
        }

        STATES.put(puid, new LockState(tuid, timer));
        PROGRESS.put(puid, Math.min(1f, (float) timer / chargeTicks));
    }

    // ═══════════════════════════════════════════════════════════
    //  攻击: 重置
    // ═══════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (STATES.get(player.getUUID()) == null) return;
        // 任何攻击都重置充能
        reset(player);
    }

    // ═══════════════════════════════════════════════════════════
    //  内部工具
    // ═══════════════════════════════════════════════════════════

    /** 完全重置：清状态 + 进度。 */
    private static void reset(Player player) {
        UUID puid = player.getUUID();
        STATES.remove(puid);
        PROGRESS.remove(puid);
    }

    /** 仅清锁状态（移开视线）。 */
    private static void clearLockState(Player player) {
        UUID puid = player.getUUID();
        STATES.remove(puid);
        PROGRESS.remove(puid);
    }

    /** 60°锥内找最近注视方向的实体，范围由 range 参数限定。 */
    private static LivingEntity findTarget(Player player, double range) {
        Vec3 eye = player.getEyePosition();
        var look = player.getLookAngle();
        LivingEntity best = null;
        double bestDot = CONE_DOT, bestDist = Double.MAX_VALUE;
        for (var entity : player.level().getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(range))) {
            if (entity == player || !entity.isAlive()) continue;
            Vec3 to = entity.position().subtract(eye);
            double d2 = to.lengthSqr();
            if (d2 > range * range) continue;
            double dot = look.dot(to) / Math.sqrt(d2);
            if (best != null && Math.abs(dot - bestDot) < 0.05) {
                if (d2 < bestDist) { bestDot = dot; best = entity; bestDist = d2; }
            } else if (dot > bestDot) {
                bestDot = dot; best = entity; bestDist = d2;
            }
        }
        return best;
    }

    private static double readAttr(Player player,
                                   net.minecraftforge.registries.RegistryObject<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        if (attr == null || !attr.isPresent()) return 0.0;
        var inst = player.getAttribute(attr.get());
        return inst != null ? inst.getValue() : 0.0;
    }
}
