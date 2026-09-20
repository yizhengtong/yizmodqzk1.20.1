package net.minecraft.client.yiz.render;

import net.minecraft.client.yiz.tizMod;
import net.minecraft.client.yiz.tool.YizDiagnostics;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 击飞后仰姿态（纯客户端），由 {@code S2CLaunchFxPayload} 两个包驱动：
 * {@code KIND_START}（开始）与 {@code KIND_END}（落地/释放）。
 *
 * <p><b>时间分配</b>：整体的 <b>2/3 用于"目标导向"（上抛/被推着走）段</b>——姿态在这 2/3 内从 0° 平滑推进到
 * <b>顶点处的 90°</b>；剩下的 <b>1/3 是下落段</b>，姿态保持 90°（平躺）。与 {@code LaunchController} 的
 * 弹道顶点位置一致（顶点也在 2/3 处）。</p>
 *
 * <p><b>为什么不用实体自身位置/速度判断</b>：击飞期间服务端把目标 tick 停掉、位置由控制器逐 tick 驱动，
 * 客户端拿到的是插值位置，自身物理又会先于服务端落地，因此"何时结束"只认服务端的结束包。</p>
 */
public final class LaunchTiltTracker {

    /** 最大后仰角（度）：90 = 从站立变成平躺。 */
    private static final float MAX_TILT = 90.0F;
    /** 上抛（目标导向）段占比：其余为下落段。与 LaunchController.APEX_FRACTION 必须一致。 */
    private static final float APEX_FRACTION = 2.0F / 3.0F;
    /** 等结束包的姿态最多多保持多少 tick（仅作丢包兜底）。 */
    private static final int HOLD_TIMEOUT_TICKS = 60;
    /** 状态表上限，防御性清理。 */
    private static final int MAX_ENTRIES = 128;

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        final int startTick;
        final int totalTicks;
        final boolean holdUntilEnd;
        float tilt;
        /** 连击刷新时置位：姿态已成立，直接保持 90° 不再重新起手。 */
        boolean held;

        State(int startTick, int totalTicks, boolean holdUntilEnd) {
            this.startTick = startTick;
            this.totalTicks = totalTicks;
            this.holdUntilEnd = holdUntilEnd;
        }
    }

    private LaunchTiltTracker() {}

    /**
     * 收到开始包：起算时间轴。
     *
     * <p>连击刷新（目标已在击飞中又被攻击）时<b>保留当前角度并直接进入保持态</b>——
     * 刷新只重置时间，不应回落再重新起手。</p>
     *
     * @param holdUntilEnd true = 生物，保持到服务端发结束包（落地）；false = 玩家，到点自动回正
     */
    public static void onLaunch(LivingEntity entity, int totalTicks, boolean holdUntilEnd) {
        if (STATES.size() > MAX_ENTRIES) STATES.clear();
        State previous = STATES.get(entity.getId());
        State state = new State(entity.tickCount, Math.max(4, totalTicks), holdUntilEnd);
        if (previous != null) {
            state.held = true;
            state.tilt = Math.max(previous.tilt, MAX_TILT * 0.9F);
        }
        STATES.put(entity.getId(), state);
        YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
            "[Launch] 客户端{} target={} 时长={}t 保持到结束={}",
            previous != null ? "刷新（保持姿态）" : "接收",
            entity.getName().getString(), totalTicks, holdUntilEnd);
    }

    /** 收到结束包（落地/释放）：瞬间回正。按 entityId 清理，实体已消失也能清掉。 */
    public static void onLaunchEnd(int entityId) {
        if (STATES.remove(entityId) != null) {
            YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
                "[Launch] 客户端复位 entityId={}", entityId);
        }
    }

    /** 当前后仰角（0 = 正常姿态，90 = 平躺）。渲染线程调用。 */
    public static float tiltOf(LivingEntity entity, float partialTick) {
        State state = STATES.get(entity.getId());
        if (state == null) return 0.0F;

        // 实体已死亡/被移除：动画必须结束（玩家在姿态中死亡、重生后残留就是这个原因）
        if (!entity.isAlive() || entity.isRemoved()) {
            STATES.remove(entity.getId());
            YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
                "[Launch] 客户端复位（实体已死亡/移除） target={}", entity.getName().getString());
            return 0.0F;
        }

        int elapsed = entity.tickCount - state.startTick;
        int endAt = state.holdUntilEnd ? state.totalTicks + HOLD_TIMEOUT_TICKS : state.totalTicks;
        // elapsed 异常（实体重建导致 tickCount 回绕变小等）也直接收尾，避免姿态挂死
        if (elapsed < 0 || elapsed >= endAt) {
            STATES.remove(entity.getId());
            YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
                "[Launch] 客户端到点回正 target={} 用时={}t", entity.getName().getString(), elapsed);
            return 0.0F;
        }

        // 前 2/3：目标导向段，姿态 0° → 90°（顶点恰好 90°）；后 1/3：下落段，保持 90°
        int ascend = Math.max(1, Math.round(state.totalTicks * APEX_FRACTION));
        float target;
        if (state.held || elapsed >= ascend) {
            target = MAX_TILT;
        } else {
            float progress = Math.max(0.0F, Math.min(1.0F, elapsed / (float) ascend));
            target = MAX_TILT * (progress * progress * (3.0F - 2.0F * progress)); // smoothstep
        }
        state.tilt += (target - state.tilt) * 0.45F;
        if (state.tilt < 0.01F) state.tilt = 0.0F;
        return state.tilt;
    }

    /** 实体卸载时清理状态。 */
    public static void forget(int entityId) {
        STATES.remove(entityId);
    }
}
