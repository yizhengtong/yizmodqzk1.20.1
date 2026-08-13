package net.minecraft.client.yiz.tool.key;

import net.minecraft.client.yiz.tizMod;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 字节码注入落点 + 密钥仓库（agent 层 {@code KeyCompareDumpTransformer} 的注入目标）。
 *
 * <p>注入点（全部只依赖<b>类型特征</b>，与目标名字无关）：</p>
 * <ul>
 *   <li>{@link #nullWalk}：改写 {@code StackWalker.walk} 调用点 → 空帧流，闸门恒通过；</li>
 *   <li>{@link #trustedCaller}：改写 {@code StackWalker.getCallerClass} 调用点 → 返回闸门自己的类；</li>
 *   <li>{@link #logCompare}：在密钥比较指令（Arrays.compare/equals/mismatch、
 *       MessageDigest.isEqual、String.equals）<b>发生前</b>转储两个操作数 ——
 *       「密钥永远活在栈上」：无论密钥如何计算/混淆，比较的那一刻必有一个操作数是期望密钥。</li>
 * </ul>
 * <p>同时维护 watch 前缀状态（{@code /yiz key watch} 控制）与捕获密钥仓库
 * （{@code /yiz key report} 展示，捕获到的密钥可直接喂给运行时层握手伪造）。</p>
 */
public final class KeyDumpBridge {

    private static final Logger LOGGER = tizMod.LOGGER;

    private static volatile Set<String> watchPrefixes = Collections.emptySet();
    private static final Map<String, byte[]> CAPTURED = new ConcurrentHashMap<>();
    private static final Map<String, String> CAPTURED_TEXT = new ConcurrentHashMap<>();
    private static final AtomicInteger LOG_COUNT = new AtomicInteger();
    private static final int LOG_LIMIT = 40;

    private KeyDumpBridge() {}

    // ==================== watch 前缀状态 ====================

    public static synchronized void setWatchPrefixes(Collection<String> prefixes) {
        watchPrefixes = prefixes == null || prefixes.isEmpty()
                ? Collections.emptySet()
                : Set.copyOf(prefixes);
    }

    public static boolean isCaptureEnabled() {
        return !watchPrefixes.isEmpty();
    }

    /** 供 agent transformer 反射调用：类名（dotted 或 internal）是否在 watch 范围内。 */
    @SuppressWarnings("unused")
    public static boolean isWatching(String className) {
        if (className == null) return false;
        String dotted = className.replace('/', '.');
        for (String p : watchPrefixes) {
            if (dotted.startsWith(p)) return true;
        }
        return false;
    }

    public static List<String> getWatchPrefixes() {
        return new ArrayList<>(watchPrefixes);
    }

    // ==================== agent 注入落点 ====================

    /** StackWalker.walk 调用点改写目标：函数作用于空帧流（擦除描述符与 walk 一致）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object nullWalk(Function<?, ?> function) {
        try {
            return ((Function) function).apply(Stream.empty());
        } catch (Throwable t) {
            return null;
        }
    }

    /** StackWalker.getCallerClass 调用点改写目标：返回闸门自己的类（必在其白名单内）。 */
    @SuppressWarnings("unused")
    public static Class<?> trustedCaller(String ownerDotted) {
        try {
            return Class.forName(ownerDotted);
        } catch (Throwable t) {
            return KeyDumpBridge.class;
        }
    }

    /** 密钥比较点转储：在比较发生前记录两个操作数；任一形似密钥即入仓库。 */
    @SuppressWarnings("unused")
    public static void logCompare(Object a, Object b, String site) {
        if (!isCaptureEnabled()) return;
        try {
            byte[] ba = a instanceof byte[] arr ? arr : null;
            byte[] bb = b instanceof byte[] arr ? arr : null;
            String sa = a instanceof String s ? s : null;
            String sb = b instanceof String s ? s : null;

            boolean aKey = ba != null && ba.length >= 16;
            boolean bKey = bb != null && bb.length >= 16;
            boolean saKey = sa != null && sa.length() >= 16;
            boolean sbKey = sb != null && sb.length() >= 16;
            if (!aKey && !bKey && !saKey && !sbKey) return;

            if (LOG_COUNT.incrementAndGet() <= LOG_LIMIT) {
                LOGGER.warn("[KeyDump] 比较点 {}: a={} b={}", site, preview(a), preview(b));
            }
            if (bKey) CAPTURED.put(site, bb);
            else if (aKey) CAPTURED.put(site, ba);
            if (sbKey) CAPTURED_TEXT.put(site, sb);
            else if (saKey) CAPTURED_TEXT.put(site, sa);
        } catch (Throwable ignored) {}
    }

    // ==================== 捕获仓库 ====================

    /** 所有捕获的密钥（比较点 site → 原始字节）。 */
    public static Map<String, byte[]> capturedBytes() {
        return new LinkedHashMap<>(CAPTURED);
    }

    public static Map<String, String> capturedText() {
        return new LinkedHashMap<>(CAPTURED_TEXT);
    }

    public static void clearCaptured() {
        CAPTURED.clear();
        CAPTURED_TEXT.clear();
        LOG_COUNT.set(0);
    }

    // ==================== 工具 ====================

    private static String preview(Object o) {
        if (o instanceof byte[] arr) return "byte[" + arr.length + "] " + StaticFieldCensus.hexPreview(arr, 48);
        if (o instanceof String s) return "\"" + s + "\"";
        return String.valueOf(o);
    }

    /** 把捕获的 String 密钥转成 UTF-8 字节（供握手伪造统一使用 byte[]）。 */
    public static byte[] toBytes(String s) {
        return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
    }
}
