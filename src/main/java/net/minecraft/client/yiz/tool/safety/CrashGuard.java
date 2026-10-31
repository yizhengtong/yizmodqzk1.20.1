package net.minecraft.client.yiz.tool.safety;

import net.minecraft.client.yiz.core.YizConfig;
import net.minecraft.client.yiz.tizMod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 入口异常隔离（安全护栏）。
 *
 * <p>只做一件事：把本模组入口（改血、死亡链、探查/属性包等）抛出的异常
 * <b>拦在服务端 tick 之外</b>——记录日志后返回，不改变正常路径的任何数值与流程。</p>
 *
 * <p>注意边界：原版/第三方在它们自己的迭代器里抛出的 CME 不在本类栈上，本类拦不到；
 * 那类问题的解法是“不在迭代期间改集合”，见 {@link TickSafeQueue}。</p>
 */
public final class CrashGuard {

    private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

    /** 每个标签前 N 次带完整堆栈，之后按 THROTTLE 抽稀，避免刷屏/磁盘 IO。 */
    private static final int DETAIL_LOGS = 3;
    private static final int THROTTLE = 200;

    private CrashGuard() {}

    /** 开关：{@code safety.crash_barrier}，默认开。读取失败按“开”处理。 */
    public static boolean enabled() {
        try {
            return YizConfig.bool("safety.crash_barrier");
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 执行一个入口动作；异常被隔离并记录。 */
    public static void run(String tag, Runnable action) {
        if (action == null) return;
        if (!enabled()) {
            action.run();
            return;
        }
        try {
            action.run();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;                       // 致命错误（OOM 等）不吞，保持 JVM 语义
        } catch (Throwable t) {
            report(tag, t);
        }
    }

    /** 有返回值的版本；异常时返回 {@code fallback}（语义 = 本次不生效）。 */
    public static <T> T call(String tag, java.util.function.Supplier<T> action, T fallback) {
        if (action == null) return fallback;
        if (!enabled()) return action.get();
        try {
            return action.get();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable t) {
            report(tag, t);
            return fallback;
        }
    }

    /** 主动上报一个异常（供 catch 块复用同一套限频）。 */
    public static void report(String tag, Throwable t) {
        String key = tag == null ? "unknown" : tag;
        int n = COUNTS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        try {
            if (n <= DETAIL_LOGS) {
                tizMod.LOGGER.error("[安全护栏] {} 捕获异常（第 {} 次），已隔离，不中断服务端 tick", key, n, t);
            } else if (n % THROTTLE == 0) {
                tizMod.LOGGER.warn("[安全护栏] {} 异常累计 {} 次（已隔离）：{}", key, n, t.toString());
            }
        } catch (Throwable ignored) {
            // 日志失败也不能反过来崩
        }
    }
}
