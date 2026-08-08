package net.minecraft.client.yiz.core;

/**
 * SPELL 伤害来源标签 ThreadLocal。
 *
 * <p>出伤前调用 {@link #set(String)} 设标签（如 {@code "ludens_echo"}），
 * 管线内任意位置 {@link #get()} 读取，出伤后 {@link #restore(String)} 还原。</p>
 *
 * <p>1.20.1 移植版：纯 JDK 依赖，逐行照搬 1.21.1。</p>
 */
public final class SpellSourceTracker {

    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();

    private SpellSourceTracker() {}

    /** 设标签，返回旧值供 restore。 */
    public static String set(String label) {
        String prev = SOURCE.get();
        SOURCE.set(label);
        return prev;
    }

    /** 还原旧标签。 */
    public static void restore(String prev) {
        if (prev != null) SOURCE.set(prev);
        else SOURCE.remove();
    }

    /** 查当前标签（null = 无标签）。 */
    public static String get() { return SOURCE.get(); }

    /** 是否有标签。 */
    public static boolean isActive() { return SOURCE.get() != null; }

    /** 清标签。 */
    public static void remove() { SOURCE.remove(); }
}
