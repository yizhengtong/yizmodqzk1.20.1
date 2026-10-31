package net.minecraft.client.yiz.tool.safety;

import net.minecraft.client.yiz.core.YizConfig;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 服务端 tick 安全队列。
 *
 * <p>凡是在“实体 tick / goal 迭代 / 实体列表迭代”期间可能修改原版结构的操作
 * （清 {@code GoalSelector}、深层移除实体等），一律先入队，由
 * {@code ServerTickEvent.Phase.END} 统一 flush —— 此时迭代器已全部退出，
 * 不会触发 {@code ConcurrentModificationException}。</p>
 *
 * <p>与旧实现 {@code server.executeIfPossible(...)} 的区别：后者在服务端线程上
 * 是<b>同步立即执行</b>（1.20.1 {@code BlockableEventLoop#execute} 在
 * {@code isSameThread()} 时直接 run），并没有真正延迟。本队列才是真正的“tick 后置”。</p>
 *
 * <p>关闭开关 {@code safety.defer_structural_ops} 后回退为立即执行（但经过
 * {@link CrashGuard} 隔离），用于对照/排障。</p>
 */
public final class TickSafeQueue {

    private record Task(String tag, Runnable action) {}

    private static final Queue<Task> QUEUE = new ConcurrentLinkedQueue<>();

    /** 单次 flush 上限：防止极端情况下长时间占用 tick。剩余任务下一 tick 继续。 */
    private static final int MAX_PER_FLUSH = 256;
    /** 队列上限：异常场景下防止无限堆积（丢弃新任务并告警）。 */
    private static final int MAX_PENDING = 4096;

    private TickSafeQueue() {}

    public static boolean enabled() {
        try {
            return YizConfig.bool("safety.defer_structural_ops");
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 入队一个结构修改任务；开关关闭时立即执行（仍经过异常隔离）。 */
    public static void enqueue(String tag, Runnable action) {
        if (action == null) return;
        if (!enabled()) {
            CrashGuard.run(tag, action);
            return;
        }
        if (QUEUE.size() >= MAX_PENDING) {
            net.minecraft.client.yiz.tizMod.LOGGER.warn("[安全队列] 待处理任务超过 {}，丢弃新任务 tag={}", MAX_PENDING, tag);
            return;
        }
        QUEUE.add(new Task(tag == null ? "unknown" : tag, action));
    }

    /** 由 ServerTickEvent(END) 调用：在服务端线程、tick 结束后统一执行。 */
    public static void flush() {
        int n = 0;
        Task t;
        while (n++ < MAX_PER_FLUSH && (t = QUEUE.poll()) != null) {
            CrashGuard.run(t.tag(), t.action());
        }
    }

    /** 关服/停服时清空，避免在拆卸阶段执行结构操作。 */
    public static void clear() {
        QUEUE.clear();
    }

    public static int size() {
        return QUEUE.size();
    }
}
