package net.minecraft.client.yiz.editor;

import net.minecraft.world.entity.player.Player;

/**
 * 破时附魔 Agent 级绕过桥梁。1.20.1 移植版。
 */
public final class PoshiBypassBridge {

    private PoshiBypassBridge() {}

    private static final ThreadLocal<Boolean> BYPASSING = new ThreadLocal<>();

    public static void beginBypass() { BYPASSING.set(true); }
    public static void endBypass() { BYPASSING.remove(); }

    @SuppressWarnings("unused")
    public static boolean shouldBypass(Object entity) {
        return BYPASSING.get() != null
            && (entity instanceof Player || entity instanceof PoshiBearer);
    }
}
