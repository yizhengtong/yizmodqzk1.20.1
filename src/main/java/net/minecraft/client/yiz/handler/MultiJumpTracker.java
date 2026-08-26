package net.minecraft.client.yiz.handler;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多段跳追踪器 — 消费 {@link YizAttributes#JUMP_COUNT} + {@link YizAttributes#JUMP_HEIGHT}
 * （仅玩家，1.21.1 移植版）。
 *
 * <p>1.20.1 差异：1.21.1 用 PlayerDataAPI（NeoForge Attachment）存储剩余次数并自动 S2C 同步；
 * 1.20.1 无此机制，改用静态 {@link ConcurrentHashMap} 存剩余次数（客户端/服务端各自 JVM 维护）。
 * 客户端乐观：ClientMultiJumpMixin 首次跳前初始化为 JUMP_COUNT、每次跳 -1、落地 recharge；
 * 服务端权威：C2S 包 tryConsume + MultiJumpRechargeHandler recharge/cap。</p>
 *
 * <p>物理反解（复刻 ExtraJumpData）：{@link #velocityFromHeight(int)} 把跳跃高度（格）
 * 反解为 Y 初速。MC 垂直方程 {@code v=(v-0.08)*0.98}，二分法 80 轮 + 缓存。校准：4→0.803。</p>
 */
public final class MultiJumpTracker {

    private MultiJumpTracker() {}

    /** 剩余多段跳次数（1.20.1 内存 Map，客户端/服务端各自维护）。 */
    private static final Map<UUID, Integer> REMAINING = new ConcurrentHashMap<>();

    /** 多段跳内置 CD（tick，5 = 0.25 秒），防长按快速消耗。纯客户端。 */
    public static final int JUMP_COOLDOWN_TICKS = 5;

    /** 默认跳跃高度（格），JUMP_HEIGHT 属性未声明时回退。 */
    private static final int DEFAULT_JUMP_HEIGHT = 4;

    // ── 跳跃物理：高度 → Y 初速（反解 + 缓存）──────────────────

    /** heightBlocks → Y 初速 缓存（避免每次跳跃重复二分反解）。 */
    private static final Map<Integer, Float> HEIGHT_TO_VELOCITY = new ConcurrentHashMap<>();

    /** 由跳跃高度（格）反解 Y 向初速。 */
    public static float velocityFromHeight(int heightBlocks) {
        if (heightBlocks <= 0) return 0f;
        return HEIGHT_TO_VELOCITY.computeIfAbsent(heightBlocks, MultiJumpTracker::solveVelocity);
    }

    /** 二分反解：找 v0 使模拟高度 ≈ target。 */
    private static float solveVelocity(int target) {
        double lo = 0.05, hi = 3.0;
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            if (simulateHeight(mid) < target) lo = mid; else hi = mid;
        }
        return (float) ((lo + hi) / 2);
    }

    /** 模拟 MC 垂直运动累加位移，返回总上升高度（格）。 */
    private static double simulateHeight(double v0) {
        double v = v0, total = 0;
        int t = 0;
        while (v > 0 && t < 400) {
            total += v;
            v = (v - 0.08) * 0.98;
            t++;
        }
        return total;
    }

    // ── 属性查询（YizAttributes 单源）──────────────────────────

    /** 最大跳跃次数（满值）= JUMP_COUNT 属性值。 */
    public static int maxJumps(Player player) {
        var inst = player.getAttribute(YizAttributes.JUMP_COUNT.get());
        if (inst == null) return 0;
        int v = (int) inst.getValue();
        return Math.max(0, v);
    }

    /** 每次跳跃高度（格）= JUMP_HEIGHT 属性值，无则默认 4。 */
    public static int jumpHeight(Player player) {
        var inst = player.getAttribute(YizAttributes.JUMP_HEIGHT.get());
        if (inst == null) return DEFAULT_JUMP_HEIGHT;
        int v = (int) inst.getValue();
        return v > 0 ? v : DEFAULT_JUMP_HEIGHT;
    }

    // ── 剩余次数（1.20.1 内存 Map）─────────────────────────────

    /** 当前剩余多段跳次数。 */
    public static int getRemaining(Player player) {
        return REMAINING.getOrDefault(player.getUUID(), 0);
    }

    /** 写入剩余次数。 */
    public static void setRemaining(Player player, int remaining) {
        if (remaining <= 0) REMAINING.remove(player.getUUID());
        else REMAINING.put(player.getUUID(), remaining);
    }

    /** 客户端只读：是否还能多段跳。未初始化且属性>0 时先乐观初始化为满值（客户端首次跳前）。 */
    public static boolean hasJump(Player player) {
        int cur = getRemaining(player);
        int full = maxJumps(player);
        if (full <= 0) return false;
        if (cur <= 0) {
            setRemaining(player, full); // 乐观初始化
            return true;
        }
        return true;
    }

    /** 服务端权威消耗一次。仅服务端调用（C2S 包处理里）。@return true = 消耗成功 */
    public static boolean tryConsume(Player player) {
        if (getRemaining(player) <= 0) return false;
        setRemaining(player, getRemaining(player) - 1);
        return true;
    }

    /** 落地充能：剩余 = JUMP_COUNT 满值。由事件/客户端落地调用。 */
    public static void recharge(Player player) {
        int full = maxJumps(player);
        if (getRemaining(player) != full) setRemaining(player, full);
    }

    /** 每 tick 服务端调用：空中 cap（剩余不超过当前装备 JUMP_COUNT 满值）。 */
    public static void tickCap(Player player) {
        int cur = getRemaining(player);
        int full = maxJumps(player);
        if (cur > full) setRemaining(player, full);
    }

    /** 玩家下线清理。 */
    public static void clear(Player player) {
        REMAINING.remove(player.getUUID());
    }
}
