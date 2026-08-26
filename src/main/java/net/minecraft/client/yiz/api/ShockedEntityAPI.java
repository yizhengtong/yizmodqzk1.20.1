package net.minecraft.client.yiz.api;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端感电状态机 — 维护"哪些实体正在感电"的持续状态（entityId → ShockedEntry）（1.21.1 移植版）。
 *
 * <p>服务端 {@code S2CShockFxPayload}（kind=0/BURST 次目标）调 {@link #putClient} 注册感电实体；
 * {@code LivingEntityRendererSurfaceLightningMixin} 渲染时查 {@link #isShocked}/{@link #getFade}
 * 决定是否叠加表面闪电 + 渐显渐隐。{@link #tick} 由 ClientTickEvent 每 tick 衰减，到期自动移除。</p>
 *
 * <p>渐隐机制：fade = min(elapsed/FADE, remaining/FADE)，前 FADE tick(=0.3s) 渐显、后 FADE tick 渐隐、中间满。</p>
 */
public final class ShockedEntityAPI {

    /** 渐显/渐隐窗口（tick，0.3s = 6 tick）。 */
    private static final int FADE = 6;

    private record ShockedEntry(int remaining, int maxDuration) {}

    private static final ConcurrentHashMap<Integer, ShockedEntry> SHOCKED = new ConcurrentHashMap<>();

    private ShockedEntityAPI() {}

    /** 注册/刷新实体感电（取 max 防短暂刷新覆盖长持续）。 */
    public static void putClient(int entityId, int durationTicks) {
        SHOCKED.merge(entityId, new ShockedEntry(durationTicks, durationTicks),
                (a, b) -> new ShockedEntry(
                        Math.max(a.remaining(), b.remaining()),
                        Math.max(a.maxDuration(), b.maxDuration())));
    }

    /** 实体当前是否处于感电状态。 */
    public static boolean isShocked(int entityId) {
        ShockedEntry e = SHOCKED.get(entityId);
        return e != null && e.remaining() > 0;
    }

    /**
     * 渐显/渐隐系数 [0,1]：前 FADE tick 渐显、后 FADE tick 渐隐、中间 1。
     * Mixin 渲染前查，0 表示完全不画。
     */
    public static float getFade(int entityId) {
        ShockedEntry e = SHOCKED.get(entityId);
        if (e == null || e.remaining() <= 0) return 0f;
        int elapsed = e.maxDuration() - e.remaining();
        float fadeIn = clamp01(elapsed / (float) FADE);
        float fadeOut = clamp01(e.remaining() / (float) FADE);
        return Math.min(fadeIn, fadeOut);
    }

    /** 每 tick 调用：全体剩余 -1，到期移除（maxDuration 不变，用于 fade 计算）。 */
    public static void tick() {
        SHOCKED.replaceAll((id, e) -> new ShockedEntry(e.remaining() - 1, e.maxDuration()));
        SHOCKED.values().removeIf(e -> e.remaining() <= 0);
    }

    public static void clear() { SHOCKED.clear(); }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
