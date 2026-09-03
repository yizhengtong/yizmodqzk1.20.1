package net.minecraft.client.yiz.itemcfg.network;

import net.minecraft.client.yiz.itemcfg.AdapterRegistry;
import net.minecraft.client.yiz.itemcfg.ConfigRegistry;
import net.minecraft.client.yiz.itemcfg.FeatureType;
import net.minecraft.client.yiz.itemcfg.ItemConfigAbolition;
import net.minecraft.client.yiz.itemcfg.ItemFeatureDiscoverer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * S2C: 返回手持物品的配置状态（功能清单 + 每功能 disabled + 物品名/mod 名）。
 * 服务端权威组装（compose），客户端渲染 GUI。
 */
public class S2CItemConfigStatePayload {

    /** declaredBy: 0=结构功能(FeatureType)，1=语义功能(adapter)。 */
    public record Entry(String feature, String zh, boolean disabled, byte declaredBy) {}

    public final String itemId;
    public final String nameKey;
    public final String modName;
    public final List<Entry> entries;

    public S2CItemConfigStatePayload(String itemId, String nameKey, String modName, List<Entry> entries) {
        this.itemId = itemId;
        this.nameKey = nameKey;
        this.modName = modName;
        this.entries = entries;
    }

    public static void encode(S2CItemConfigStatePayload payload, FriendlyByteBuf buf) {
        buf.writeUtf(payload.itemId);
        buf.writeUtf(payload.nameKey);
        buf.writeUtf(payload.modName);
        buf.writeVarInt(payload.entries.size());
        for (Entry e : payload.entries) {
            buf.writeUtf(e.feature());
            buf.writeUtf(e.zh());
            buf.writeBoolean(e.disabled());
            buf.writeByte(e.declaredBy());
        }
    }

    public static S2CItemConfigStatePayload decode(FriendlyByteBuf buf) {
        String itemId = buf.readUtf();
        String nameKey = buf.readUtf();
        String modName = buf.readUtf();
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(new Entry(buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readByte()));
        }
        return new S2CItemConfigStatePayload(itemId, nameKey, modName, entries);
    }

    /** 客户端接收：交给客户端处理器（打开 GUI 或刷新已开 GUI）。 */
    public static void handle(S2CItemConfigStatePayload payload, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> net.minecraft.client.yiz.itemcfg.client.ItemConfigClientHandler.onStateReceived(payload));
        ctx.get().setPacketHandled(true);
    }

    /** 服务端组装：结构功能 + 语义功能，每项取该玩家生效 disabled 值。 */
    public static S2CItemConfigStatePayload compose(ServerPlayer sp, ResourceLocation id) {
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) return null;

        EnumSet<FeatureType> structural = ItemFeatureDiscoverer.getOrScan(id);
        List<Entry> entries = new ArrayList<>();
        for (FeatureType ft : structural) {
            // 全局废除型（持续效果/背包tick/穿戴）状态读 abolish.json（op 配置）；其余 per-player
            boolean disabled = ItemConfigAbolition.isAbolitionFeature(ft)
                    ? ItemConfigAbolition.isAbolished(id, ft.name())
                    : ConfigRegistry.isDisabled(sp.getUUID(), id, ft.name());
            entries.add(new Entry(ft.name(), ft.zhName(), disabled, (byte) 0));
        }
        // 通用"持有/背包效果"开关（所有物品都显示，关闭后由适配库驱动效果失效）
        entries.add(new Entry("HELD_EFFECTS", "持有/背包效果",
                ConfigRegistry.isDisabled(sp.getUUID(), id, "HELD_EFFECTS"), (byte) 2));
        for (AdapterRegistry.AdapterFeature af : AdapterRegistry.find(id)) {
            entries.add(new Entry(af.feature(), af.zh(),
                    ConfigRegistry.isDisabled(sp.getUUID(), id, af.feature()), (byte) 1));
        }
        return new S2CItemConfigStatePayload(
                id.toString(), ItemFeatureDiscoverer.itemNameKey(id), ItemFeatureDiscoverer.modName(id), entries);
    }
}
