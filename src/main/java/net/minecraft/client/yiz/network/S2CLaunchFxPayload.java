package net.minecraft.client.yiz.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 击飞姿态 S2C 包：告知客户端「实体 X 被击飞，总时长 T tick，起飞高度 Y」。
 *
 * <p><b>为什么必须显式同步</b>：击飞期间服务端把目标自身 tick 停掉（AI/重力/摩擦/第三方每 tick 逻辑全停），
 * 位置由控制器逐 tick 驱动；客户端因此拿不到可信的垂直速度，自身物理也会先于服务端落地
 * （1 格高的弹道尤其明显），靠"看运动"反推姿态必然失效。改为按包驱动时间轴后，客户端只依赖
 * 包里的时长与起飞高度，与实体自身的客户端物理无关。</p>
 */
public class S2CLaunchFxPayload {

    /** 0 = 开始击飞（客户端起算时间轴）；1 = 击飞结束/落地（客户端瞬间回正）。 */
    public static final int KIND_START = 0;
    public static final int KIND_END = 1;

    final int kind;
    final int entityId;
    final int totalTicks;
    final double startY;
    /** true = 一直保持姿态直到收到结束包（生物：落地才回正）；false = 到点自动回正（玩家：只播一段倒下动画）。 */
    final boolean holdUntilEnd;

    public S2CLaunchFxPayload(int kind, int entityId, int totalTicks, double startY, boolean holdUntilEnd) {
        this.kind = kind;
        this.entityId = entityId;
        this.totalTicks = totalTicks;
        this.startY = startY;
        this.holdUntilEnd = holdUntilEnd;
    }

    public static void encode(S2CLaunchFxPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.kind);
        buf.writeVarInt(payload.entityId);
        buf.writeVarInt(payload.totalTicks);
        buf.writeDouble(payload.startY);
        buf.writeBoolean(payload.holdUntilEnd);
    }

    public static S2CLaunchFxPayload decode(FriendlyByteBuf buf) {
        return new S2CLaunchFxPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readDouble(), buf.readBoolean());
    }

    public static void handle(S2CLaunchFxPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // 结束包按 entityId 清状态，不依赖实体还能在客户端找到：
            // 实体被移除/玩家重生后旧实体已不存在，若在这里 return，姿态会永远挂着。
            if (payload.kind != KIND_START) {
                net.minecraft.client.yiz.render.LaunchTiltTracker.onLaunchEnd(payload.entityId);
                return;
            }
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return;
            net.minecraft.world.entity.Entity entity = mc.level.getEntity(payload.entityId);
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) return;
            net.minecraft.client.yiz.render.LaunchTiltTracker.onLaunch(
                living, payload.totalTicks, payload.holdUntilEnd);
        });
        ctx.get().setPacketHandled(true);
    }
}
