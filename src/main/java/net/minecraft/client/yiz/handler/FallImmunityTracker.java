package net.minecraft.client.yiz.handler;

import net.minecraft.world.entity.player.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 掉落伤害免疫标记（奔雷疾等技能使用）。 */
public final class FallImmunityTracker {

    private static final ConcurrentHashMap<UUID, Boolean> IMMUNE = new ConcurrentHashMap<>();

    private FallImmunityTracker() {}

    public static void grant(Player player) {
        IMMUNE.put(player.getUUID(), true);
    }

    /** 消费标记，返回是否免疫。 */
    public static boolean consume(Player player) {
        return IMMUNE.remove(player.getUUID()) != null;
    }
}
