package net.minecraft.client.yiz.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.yiz.api.TargetFrameManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 锁定系统客户端事件注册（1.21.1 移植）：
 * <ul>
 *   <li>注册 {@link LockOnProvider} 到 TargetFrameManager（锁定目标 + 充能供应）；</li>
 *   <li>客户端每 tick 聚合本地玩家 NBT 属性（HUIXIN/KEGONG 供 LockOnProvider 渲染读取）；</li>
 *   <li>注册 {@code LockOutlineRenderer}（彩色半透明轮廓描边，透明度=充能）。</li>
 * </ul>
 * 服务端调用 init() 无害（客户端专属事件/供应者服务端不触发/不查询）。
 */
public final class LockOnClientEvents {

    private LockOnClientEvents() {}

    /** 初始化（tizMod commonSetup 调用一次）。 */
    public static void init() {
        TargetFrameManager.register(new LockOnProvider());
        MinecraftForge.EVENT_BUS.register(LockOnClientEvents.class);
        MinecraftForge.EVENT_BUS.register(net.minecraft.client.yiz.client.render.LockOutlineRenderer.class);
    }

    /** 客户端每 tick：聚合本地玩家 NBT 属性（服务端已聚合 ServerPlayer，客户端聚合 LocalPlayer 供渲染）。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.level.isClientSide()) {
            net.minecraft.client.yiz.tool.attribute.NbtAttributeAggregator.aggregate(mc.player);
        }
    }
}
