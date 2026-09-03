package net.minecraft.client.yiz.itemcfg.network;

import net.minecraft.client.yiz.itemcfg.AdapterRegistry;
import net.minecraft.client.yiz.itemcfg.ConfigRegistry;
import net.minecraft.client.yiz.itemcfg.FeatureType;
import net.minecraft.client.yiz.itemcfg.ItemConfigAbolition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * C2S: 客户端切换某物品某功能的开关（服务端权威，写透 players.json）。
 */
public class C2SItemConfigTogglePayload {

    final String itemId;
    final String feature;
    final boolean disabled;

    public C2SItemConfigTogglePayload(String itemId, String feature, boolean disabled) {
        this.itemId = itemId;
        this.feature = feature;
        this.disabled = disabled;
    }

    public static void encode(C2SItemConfigTogglePayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.itemId);
        buf.writeUtf(payload.feature);
        buf.writeBoolean(payload.disabled);
    }

    public static C2SItemConfigTogglePayload decode(FriendlyByteBuf buf) {
        return new C2SItemConfigTogglePayload(buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }

    /** 客户端发送。 */
    public static void send(String itemId, String feature, boolean disabled) {
        net.minecraft.client.yiz.network.NetworkHandler.CHANNEL.sendToServer(
                new C2SItemConfigTogglePayload(itemId, feature, disabled));
    }

    /** 服务端接收：校验功能存在 → 写玩家配置 → 回发最新状态。 */
    public static void handle(C2SItemConfigTogglePayload payload, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (!(ctx.get().getSender() instanceof ServerPlayer)) return;
            ServerPlayer sp = (ServerPlayer) ctx.get().getSender();
            ResourceLocation id = ResourceLocation.tryParse(payload.itemId);
            if (id == null) return;
            // 防伪造：只接受已知功能（结构 FeatureType 或 adapter 声明）
            boolean known = FeatureType.fromId(payload.feature) != null
                    || AdapterRegistry.isKnown(id, payload.feature)
                    || "HELD_EFFECTS".equals(payload.feature);
            if (!known) return;

            FeatureType ft = FeatureType.fromId(payload.feature);
            boolean isAbolition = ft != null && ItemConfigAbolition.isAbolitionFeature(ft);
            if (isAbolition) {
                // 全局废除型（持续效果/背包tick/穿戴）：需 op，改 abolish.json（VTable 全局生效）
                if (sp.hasPermissions(2)) {
                    if (payload.disabled) ItemConfigAbolition.abolish(id, payload.feature);
                    else ItemConfigAbolition.restore(id, payload.feature);
                }
            } else {
                // per-player 型
                ConfigRegistry.setPlayerDisabled(sp.getUUID(), id, payload.feature, payload.disabled);
            }
            net.minecraft.client.yiz.itemcfg.ItemConfigGates.invalidate(sp.getUUID());

            S2CItemConfigStatePayload state = S2CItemConfigStatePayload.compose(sp, id);
            if (state != null) {
                net.minecraft.client.yiz.network.NetworkHandler.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> sp), state);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
