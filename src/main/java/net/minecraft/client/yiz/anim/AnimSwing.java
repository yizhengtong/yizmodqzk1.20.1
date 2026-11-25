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
    /** 上次播放动画的时间（毫秒）：1.8 风格武器攻击间隔接近 0，需要节流，否则每 tick 重播。 */
    private static final Map<UUID, Long> LAST_ANIM_MS = new ConcurrentHashMap<>();
    /** 与最小播放时长（0.3 秒）对齐。 */
    private static final long MIN_ANIM_GAP_MS = 300L;

    private AnimSwing() {}

    public static void tryAttack(ServerPlayer player) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        // 只按"第三人称配置"广播；第一人称是本地播放，不经过服务端
        if (!AnimLibrary.hasThirdPerson(held)) {
            return;
        }
        // 无冷却武器（攻击间隔≈0）按住攻击时每 tick 都会进来，这里按动画最短时长节流；
        // 伤害已经正常结算，只是不重复广播动画。
        long now = System.currentTimeMillis();
        Long last = LAST_ANIM_MS.get(player.getUUID());
        if (last != null && now - last < MIN_ANIM_GAP_MS) {
            return;
        }
        LAST_ANIM_MS.put(player.getUUID(), now);
        int combo = COMBO_INDEX.getOrDefault(player.getUUID(), 0);
        AnimClip clip = AnimLibrary.comboClip(held, combo);
        if (clip == null) {
            return;
        }

        COMBO_INDEX.put(player.getUUID(), combo + 1);
        if (COMBO_INDEX.size() > 512) {
            COMBO_INDEX.clear();
        }

        // 动画时长 = 玩家当前主手物品的攻击间隔（攻击越快播得越快）
        float attackSeconds = (float) (player.getCurrentItemAttackStrengthDelay() / 20.0);
        if (attackSeconds < 0.05F) {
            attackSeconds = 0.05F;
        }

        NetworkHandler.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new S2CAnimAttackPayload(player.getId(), clip.name(), attackSeconds));
    }

    public static void forget(UUID uuid) {
        COMBO_INDEX.remove(uuid);
        LAST_ANIM_MS.remove(uuid);
    }
}
