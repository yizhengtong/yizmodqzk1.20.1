package net.minecraft.client.yiz.handler;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 玩家复活清负面状态（2026-08-12，配合攻击线后门白名单）。
 *
 * <p>辖界者涨跌多空攻击线会对玩家施加：等比累积（{@link
 * net.minecraft.client.yiz.tool.health.EntityASMUtil#clearDreamAccum}）、delta 软压通道、
 * 永久禁疗 / 叠加禁疗 / 临时禁疗（VitalitySeverance）。玩家死亡重生（{@link PlayerEvent.Clone}）
 * 或其它复活途径（{@link PlayerEvent.PlayerRespawnEvent}）时 UUID 不变，按 UUID 存储的状态会残留
 * （delta 还会经 NBT 从旧实体克隆回新实体）——必须在此统一清除，避免「复活后仍被软压 / 禁疗 /
 * 一击致死」。</p>
 */
@Mod.EventBusSubscriber(modid = net.minecraft.client.yiz.tizMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerRespawnHandler {

    private PlayerRespawnHandler() {}

    /** 玩家死亡重生（旧实体 → 新实体，UUID 不变）。 */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        clearNegativeStates(event.getEntity());
    }

    /** 玩家复活（覆盖非死亡重生途径，幂等）。 */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        clearNegativeStates(event.getEntity());
    }

    /** 清除辖界者攻击线施加到玩家的全部负面状态（UUID 维度）。 */
    private static void clearNegativeStates(net.minecraft.world.entity.player.Player player) {
        if (player == null) return;
        try {
            net.minecraft.client.yiz.tool.health.EntityASMUtil.clearDreamAccum(player);      // 等比累积
        } catch (Throwable ignored) {}
        try {
            net.minecraft.client.yiz.tool.health.EntityASMUtil.setHealthDelta(player, 0);    // delta 软压通道归零
        } catch (Throwable ignored) {}
        try {
            net.minecraft.client.yiz.tool.health.VitalitySeveranceConfig.remove(player);     // 永久禁疗
        } catch (Throwable ignored) {}
        try {
            net.minecraft.client.yiz.tool.health.VitalitySeveranceHandler.clear(player);     // 叠加禁疗
            net.minecraft.client.yiz.tool.health.VitalitySeveranceHandler.removeTempBan(player); // 临时禁疗
        } catch (Throwable ignored) {}
    }
}
