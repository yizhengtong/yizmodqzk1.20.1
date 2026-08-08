package net.minecraft.client.yiz.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：奔雷袭 AoE 窗口同步包。
 * 服务端 tickBenleixi 创建/续期窗口时发送，客户端收到后本地计时，
 * AutoAttackMixin 在窗口期内自动蓄力攻击。
 *
 * <p>1.20.1 移植：1.21.1 原为 {@code CustomPacketPayload}（record + StreamCodec + PacketDistributor）。
 * 1.20.1 Forge 无 Payload 系统，改为 {@code SimpleChannel#registerMessage} 风格 POJO：
 * {@link #toBytes}（encoder）/ {@link #fromBytes}（decoder）/ {@link #handle}（consumer）。</p>
 *
 * <p>⚠️ 1.20.1 网络层尚未接入 SimpleChannel（下游 tizMod 未注册通道），
 * {@link #sendWindowStart}/{@link #sendWindowEnd} 暂为 TODO 空实现，注册通道后启用。</p>
 *
 * @param active true=窗口激活，客户端开始自动攻击；false=窗口结束
 * @param durationTicks 窗口剩余 tick 数（active=true 时有效）
 */
public class S2CBenleixiWindowPayload {

    private boolean active;
    private int durationTicks;

    /** Forge 消息解码所需的无参构造（配合 SimpleChannel decoder 的 Function）。 */
    public S2CBenleixiWindowPayload() {
        this(false, 0);
    }

    public S2CBenleixiWindowPayload(boolean active, int durationTicks) {
        this.active = active;
        this.durationTicks = durationTicks;
    }

    public boolean active() { return active; }
    public int durationTicks() { return durationTicks; }

    // ── 编解码（1.20.1 SimpleChannel）──

    /** 编码（registerMessage 的 encoder BiConsumer 可直接引用 S2CBenleixiWindowPayload::toBytes）。 */
    public void toBytes(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeVarInt(durationTicks);
    }

    /** 解码（registerMessage 的 decoder Function）。 */
    public static S2CBenleixiWindowPayload fromBytes(FriendlyByteBuf buf) {
        return new S2CBenleixiWindowPayload(buf.readBoolean(), buf.readVarInt());
    }

    /** 客户端收到后更新本地窗口计时器（registerMessage 的 consumer 引用 handle）。 */
    public void handle(Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            // TODO(1.20.1-port): 依赖 network/BenleixiWindowClientTracker（1.21.1，未移植）。
            // 原逻辑：BenleixiWindowClientTracker.set(player, active, durationTicks);
        });
        ctx.setPacketHandled(true);
    }

    // ── 服务端发送 ──

    /** 窗口激活时发送 */
    public static void sendWindowStart(ServerPlayer player, int durationTicks) {
        // TODO(1.20.1-port): 1.20.1 网络层未接入 SimpleChannel。待下游注册通道后：
        // CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
        //              new S2CBenleixiWindowPayload(true, durationTicks));
    }

    /** 窗口结束时发送 */
    public static void sendWindowEnd(ServerPlayer player) {
        // TODO(1.20.1-port): 同上，包体 new S2CBenleixiWindowPayload(false, 0)。
    }
}
