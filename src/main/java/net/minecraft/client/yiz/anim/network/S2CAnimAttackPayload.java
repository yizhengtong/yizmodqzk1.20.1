package net.minecraft.client.yiz.anim.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.yiz.anim.AnimClientState;
import net.minecraft.client.yiz.anim.AnimClip;
import net.minecraft.client.yiz.anim.AnimLibrary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** S2C：玩家 entityId 开始播放 clipName，playSeconds = 该动画的完整时长（全玩家可见）。 */
public class S2CAnimAttackPayload {

    final int entityId;
    final String clipName;
    final float playSeconds;

    public S2CAnimAttackPayload(int entityId, String clipName, float playSeconds) {
        this.entityId = entityId;
        this.clipName = clipName;
        this.playSeconds = playSeconds;
    }

    public static void encode(S2CAnimAttackPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.entityId);
        buf.writeUtf(payload.clipName, 64);
        buf.writeFloat(payload.playSeconds);
    }

    public static S2CAnimAttackPayload decode(FriendlyByteBuf buf) {
        return new S2CAnimAttackPayload(buf.readVarInt(), buf.readUtf(64), buf.readFloat());
    }

    public static void handle(S2CAnimAttackPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return;
            }
            Entity entity = mc.level.getEntity(payload.entityId);
            if (!(entity instanceof Player)) {
                return;
            }
            AnimClip clip = AnimLibrary.get(payload.clipName);
            if (clip == null) {
                return;
            }
            AnimClientState.start(payload.entityId, clip, payload.playSeconds);
        });
        ctx.get().setPacketHandled(true);
    }
}
