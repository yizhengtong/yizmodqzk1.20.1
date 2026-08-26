package net.minecraft.client.yiz.network;

import net.minecraft.client.yiz.handler.MultiJumpTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: 客户端请求消耗一次多段跳（空中再跳，1.21.1 移植版，SimpleChannel）。
 * 服务端权威消耗剩余次数（内存 Map）。1.20.1 无 PlayerDataAPI S2C 同步，客户端乐观预测。
 */
public class C2SMultiJumpPayload {

    public C2SMultiJumpPayload() {}

    public static void encode(C2SMultiJumpPayload payload, FriendlyByteBuf buf) {}

    public static C2SMultiJumpPayload decode(FriendlyByteBuf buf) {
        return new C2SMultiJumpPayload();
    }

    /** 客户端发送。 */
    public static void send() {
        NetworkHandler.CHANNEL.sendToServer(new C2SMultiJumpPayload());
    }

    /** 服务端接收：权威消耗一次多段跳。 */
    public static void handle(C2SMultiJumpPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getSender() instanceof ServerPlayer) {
                ServerPlayer sp = (ServerPlayer) ctx.get().getSender();
                MultiJumpTracker.tryConsume(sp);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
