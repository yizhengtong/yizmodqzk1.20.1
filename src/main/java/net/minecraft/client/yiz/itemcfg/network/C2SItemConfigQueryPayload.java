package net.minecraft.client.yiz.itemcfg.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * C2S: 客户端请求当前手持物品的配置状态（Shift+U 按下时发送）。
 * 服务端组装 S2C 状态并定向回发。
 */
public class C2SItemConfigQueryPayload {

    final String itemId;

    public C2SItemConfigQueryPayload(String itemId) {
        this.itemId = itemId;
    }

    public static void encode(C2SItemConfigQueryPayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.itemId);
    }

    public static C2SItemConfigQueryPayload decode(FriendlyByteBuf buf) {
        return new C2SItemConfigQueryPayload(buf.readUtf());
    }

    /** 客户端发送。 */
    public static void send(String itemId) {
        net.minecraft.client.yiz.network.NetworkHandler.CHANNEL.sendToServer(new C2SItemConfigQueryPayload(itemId));
    }

    /** 服务端接收：组装状态回发。 */
    public static void handle(C2SItemConfigQueryPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (!(ctx.get().getSender() instanceof ServerPlayer)) return;
            ServerPlayer sp = (ServerPlayer) ctx.get().getSender();
            ResourceLocation id = ResourceLocation.tryParse(payload.itemId);
            if (id == null) return;
            S2CItemConfigStatePayload state = S2CItemConfigStatePayload.compose(sp, id);
            if (state != null) {
                net.minecraft.client.yiz.network.NetworkHandler.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> sp), state);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
