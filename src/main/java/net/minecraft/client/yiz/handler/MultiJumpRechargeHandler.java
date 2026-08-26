package net.minecraft.client.yiz.handler;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.TickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多段跳落地充能 + 空中 cap — 服务端权威（仅玩家）（1.21.1 移植版）。
 *
 * <p>等价 yizxian {@code ExtraJumpHandler}，三路径充能：
 * <ol>
 *   <li>{@link LivingFallEvent}：正常摔落落地（有 fallDistance）→ 充满</li>
 *   <li>{@code isFallFlying()} 下降沿：鞘翅飞行结束 → 充满</li>
 *   <li>稳定地面 3 tick：{@code onGround && !flying && |vy|<0.1} 连续 3 tick → 充满</li>
 * </ol>
 * 每 tick 还做空中 cap（剩余不超过 JUMP_COUNT 满值，处理卸装备）。</p>
 */
public final class MultiJumpRechargeHandler {

    private MultiJumpRechargeHandler() {}

    /** 稳定地面充能所需连续 tick（3 tick = 150ms）。 */
    private static final int STABLE_GROUND_TICKS = 3;

    /** 玩家 → 是否上一 tick 在飞行（检测下降沿）。 */
    private static final Map<UUID, Boolean> WAS_FLYING = new ConcurrentHashMap<>();
    /** 玩家 → 连续稳定地面 tick 数。 */
    private static final Map<UUID, Integer> GROUND_TICKS = new ConcurrentHashMap<>();

    /** LivingFallEvent：摔落落地充满。由 tizMod 注册。 */
    public static void onLivingFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide()) return;
        rechargeAll(player);
    }

    /** PlayerTickEvent（phase END）：飞行下降沿 + 稳定地面充能 + 空中 cap。由 tizMod 注册。 */
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player == null || player.level().isClientSide()) return;
        UUID uuid = player.getUUID();

        // 路径②：isFallFlying 下降沿
        boolean nowFlying = player.isFallFlying();
        boolean wasFlying = WAS_FLYING.getOrDefault(uuid, false);
        WAS_FLYING.put(uuid, nowFlying);
        if (wasFlying && !nowFlying) {
            rechargeAll(player);
            GROUND_TICKS.put(uuid, 0);
            MultiJumpTracker.tickCap(player);
            return;
        }

        // 路径③：稳定地面充满
        boolean onGround = player.onGround();
        boolean verticalCalm = Math.abs(player.getDeltaMovement().y()) < 0.1;
        if (onGround && !nowFlying && verticalCalm) {
            int ticks = GROUND_TICKS.getOrDefault(uuid, 0) + 1;
            GROUND_TICKS.put(uuid, ticks);
            if (ticks >= STABLE_GROUND_TICKS) {
                rechargeAll(player);
                GROUND_TICKS.put(uuid, STABLE_GROUND_TICKS); // 避免每 tick 重复
            }
        } else {
            GROUND_TICKS.put(uuid, 0);
        }

        // 空中 cap（每 tick）
        MultiJumpTracker.tickCap(player);
    }

    /** 充满到 JUMP_COUNT 满值。 */
    private static void rechargeAll(Player player) {
        MultiJumpTracker.recharge(player);
    }

    /** 玩家下线清理（防 Map 泄漏）。 */
    public static void clear(Player player) {
        UUID uuid = player.getUUID();
        WAS_FLYING.remove(uuid);
        GROUND_TICKS.remove(uuid);
    }
}
