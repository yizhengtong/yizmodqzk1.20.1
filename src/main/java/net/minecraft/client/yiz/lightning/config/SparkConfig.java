package net.minecraft.client.yiz.lightning.config;

/**
 * 火花系统运行时开关 — 总开关 + 三分项（端点/命中/表面）。
 *
 * <p>全默认开，运行时可动态关闭。getter 做<b>总开关短路</b>：分项仅在总开关开启时生效，
 * 调用方只查 {@link #isEndpoint()} 等一处即可。纯静态 boolean、无持久化（视觉开关，
 * 非世界级配置）；切换走 {@code /yzarc spark} 子命令。</p>
 */
public final class SparkConfig {

    private static boolean enabled = true;
    private static boolean endpoint = true;
    private static boolean hit = true;
    private static boolean surface = true;

    private SparkConfig() {}

    /** 总开关。 */
    public static boolean isEnabled() { return enabled; }
    /** 端点持续迸发（含总开关短路）。 */
    public static boolean isEndpoint() { return enabled && endpoint; }
    /** 命中瞬间迸发。 */
    public static boolean isHit() { return enabled && hit; }
    /** 表面游离溅射。 */
    public static boolean isSurface() { return enabled && surface; }

    /** 各 toggle 返回新值，供命令回显。 */
    public static boolean toggleEnabled() { return enabled = !enabled; }
    public static boolean toggleEndpoint() { return endpoint = !endpoint; }
    public static boolean toggleHit() { return hit = !hit; }
    public static boolean toggleSurface() { return surface = !surface; }

    /** 一键全开/全关。 */
    public static void setAll(boolean v) { enabled = endpoint = hit = surface = v; }
}
