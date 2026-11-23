package net.minecraft.client.yiz.anim;

import net.minecraft.client.yiz.anim.network.S2CAnimAttackPayload;
import net.minecraft.client.yiz.network.NetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端挥砍调度：按攻击顺序选择连段并广播。
 *
 * <p><b>不再用攻击间隔压缩动画</b>：每次攻击只负责把"下一段动画"放进队列，
 * 每段动画都以编辑器里填写的 {@link AnimClip#duration()} 完整播放；
 * 客户端收到后按顺序排队，当前一段播完才会开始下一段（见 {@code AnimClientState}）。
 * 也就是动画和实际伤害/攻击节奏解耦，伤害照常结算，动画自己排队播完。</p>
 */
public final class AnimSwing {

    /** 每个玩家的连段下标（按攻击次数递增，与动画时长无关）。 */
    private static final Map<UUID, Integer> COMBO_INDEX = new ConcurrentHashMap<>();

    private AnimSwing() {}

    public static void tryAttack(ServerPlayer player) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!AnimLibrary.isAnimWeapon(held)) {
            return;
        }
        int combo = COMBO_INDEX.getOrDefault(player.getUUID(), 0);
        AnimClip clip = AnimLibrary.comboClip(held, combo);
        if (clip == null) {
            return;
        }

        COMBO_INDEX.put(player.getUUID(), combo + 1);
        if (COMBO_INDEX.size() > 512) {
            COMBO_INDEX.clear();
        }

        NetworkHandler.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new S2CAnimAttackPayload(player.getId(), clip.name(), clip.duration()));
    }

    public static void forget(UUID uuid) {
        COMBO_INDEX.remove(uuid);
    }
}
