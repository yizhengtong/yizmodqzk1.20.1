package net.minecraft.client.yiz.api;

import net.minecraft.world.entity.player.Player;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原版暴击标记 — 桥接 {@code CriticalHitEvent} 与 {@code LivingDamageEvent.Pre}。
 *
 * <h3>为什么需要？</h3>
 * <p>原版暴击（跳跃攻击 1.5x）在 {@code Player.attack()} 内部发生，
 * 到 {@code LivingDamageEvent.Pre} 时 1.5x 已 baked 进 originalDamage。
 * 我们的自定义暴击系统需要知道"原版是否已经乘以 1.5x"，以避免：
 * <ul>
 * <li>原版暴击 + 自定义暴击 → 1.5×1.5=2.25 双倍叠加</li>
 * <li>非原版暴击 + 自定义暴击 → 只加 bonus 没加基础 1.5x</li>
 * </ul>
 * </p>
 *
 * <h3>工作流</h3>
 * <ol>
 * <li>{@code CriticalHitEvent} → {@link #mark(Player, boolean)} 写入标记</li>
 * <li>{@code LivingDamageEvent.Pre} → {@link #consume(Player)} 读取并清除</li>
 * </ol>
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} 支撑的 Set。</p>
 *
 * <p>1.20.1 移植版：纯 JDK 依赖，逐行照搬 1.21.1。</p>
 */
public final class CritTracker {

    private static final Set<UUID> VANILLA_CRIT_FLAG =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    private CritTracker() {}

    /**
     * 记录玩家当前攻击是否为原版暴击（1.5x 已 baked）。
     * 在 {@code CriticalHitEvent} 中调用。
     */
    public static void mark(Player player, boolean wasCrit) {
        if (wasCrit) {
            VANILLA_CRIT_FLAG.add(player.getUUID());
        }
    }

    /**
     * 只读不删。供 modifyHurtAmount 判断当前攻击是否已被近战暴击处理过。
     */
    public static boolean isMarked(Player player) {
        return VANILLA_CRIT_FLAG.contains(player.getUUID());
    }

    /**
     * 读取并清除标记。
     * 在 {@code LivingDamageEvent.Pre} 中调用。
     * @return true = 原版暴击已发生（originalDamage 已含 1.5x）
     */
    public static boolean consume(Player player) {
        return VANILLA_CRIT_FLAG.remove(player.getUUID());
    }
}
