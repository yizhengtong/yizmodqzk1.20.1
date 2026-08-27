package net.minecraft.client.yiz.api;

import net.minecraft.world.entity.player.Player;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 锁定框管理者（1.21.1 移植版）— 客户端渲染前调用，选出最高优先级的活跃供应者。
 */
public final class TargetFrameManager {

    private static final CopyOnWriteArrayList<TargetFrameProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private TargetFrameManager() {}

    public static void register(TargetFrameProvider provider) {
        PROVIDERS.add(provider);
    }

    public static void unregister(TargetFrameProvider provider) {
        PROVIDERS.remove(provider);
    }

    /** 返回当前玩家最高优先级的有效供应者（有目标），没有返回 null。 */
    public static TargetFrameProvider getBest(Player player) {
        TargetFrameProvider best = null;
        int bestPri = Integer.MIN_VALUE;
        for (TargetFrameProvider p : PROVIDERS) {
            if (p.getTarget(player) == null) continue;
            if (p.getPriority() > bestPri) {
                bestPri = p.getPriority();
                best = p;
            }
        }
        return best;
    }
}
