package net.minecraft.client.yiz.handler;

import net.minecraft.client.yiz.core.LaunchController;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 击飞弹道的服务端驱动源。
 *
 * <p>飞行期间目标自身的 tick 被停掉（见 {@code LivingEntityMixin#yizmodqzk$launchTickStop}），
 * 因此推进弹道不能依赖实体自身的 tick 回调，改由服务端 tick 事件统一驱动。</p>
 */
public final class LaunchTickHandler {

    private LaunchTickHandler() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        LaunchController.tickServer();
    }
}
