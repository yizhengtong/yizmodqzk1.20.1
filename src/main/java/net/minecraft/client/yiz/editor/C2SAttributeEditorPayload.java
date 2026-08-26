package net.minecraft.client.yiz.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: 客户端请求对属性编辑台放置槽中的物品应用属性值（1.20.1 移植版，SimpleChannel）。
 *
 * <p>玩家在属性编辑台 GUI 中点击某行属性 → 客户端发此 payload
 * → 服务端从 BlockEntity 取出放置槽物品 → 调用对应 setter → 写回。</p>
 */
public class C2SAttributeEditorPayload {

    final String attrId;
    final double value;

    public C2SAttributeEditorPayload(String attrId, double value) {
        this.attrId = attrId;
        this.value = value;
    }

    public static void encode(C2SAttributeEditorPayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.attrId);
        buf.writeDouble(payload.value);
    }

    public static C2SAttributeEditorPayload decode(FriendlyByteBuf buf) {
        return new C2SAttributeEditorPayload(buf.readUtf(), buf.readDouble());
    }

    /** 客户端发送。 */
    public static void send(String attrId, double value) {
        net.minecraft.client.yiz.network.NetworkHandler.CHANNEL.sendToServer(new C2SAttributeEditorPayload(attrId, value));
    }

    /** 服务端接收：应用属性值到放置槽物品。 */
    public static void handle(C2SAttributeEditorPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (!(ctx.get().getSender() instanceof ServerPlayer)) return;
            ServerPlayer player = (ServerPlayer) ctx.get().getSender();
            if (!(player.containerMenu instanceof AttributeEditorMenu)) return;
            AttributeEditorMenu menu = (AttributeEditorMenu) player.containerMenu;

            // 从 BlockEntity 取放置槽物品
            ItemStack stack = menu.getContainer().getItem(0);
            if (stack.isEmpty()) return;

            // 查 EditableAttribute → 调 setter
            EditableAttribute attr = EditableAttribute.getAll().stream()
                .filter(a -> a.id().equals(payload.attrId))
                .findFirst().orElse(null);
            if (attr == null) return;

            attr.setter().accept(stack, payload.value);
            menu.getContainer().setItem(0, stack);
            menu.broadcastChanges();
        });
        ctx.get().setPacketHandled(true);
    }
}
