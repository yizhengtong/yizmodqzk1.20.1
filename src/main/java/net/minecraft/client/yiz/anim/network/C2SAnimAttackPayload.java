package net.minecraft.client.yiz.anim.network;

import net.minecraft.client.yiz.anim.AnimSwing;
import net.minecraft.client.yiz.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** C2S：客户端按了一次攻击键，请求服务端开始一段玩家动画。 */
public class C2SAnimAttackPayload {

    public C2SAnimAttackPayload() {}

    public static void send() {
        NetworkHandler.CHANNEL.sendToServer(new C2SAnimAttackPayload());
    }

    public static void encode(C2SAnimAttackPayload payload, FriendlyByteBuf buf) {}

    public static C2SAnimAttackPayload decode(FriendlyByteBuf buf) {
        return new C2SAnimAttackPayload();
    }

    public static void handle(C2SAnimAttackPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) {
                AnimSwing.tryAttack(sender);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
