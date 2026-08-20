package net.minecraft.client.yiz.tool.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 健康值修改调度器
 * 管理所有周期性健康值修改任务。
 *
 * 每个实体可以注册多个定时任务，每 tick 检查是否应该触发。
 * 与 BuiltInTriggers.TickDrivenTrigger 配合使用。
 */
public final class HealthModificationScheduler {

    private static final Map<UUID, List<ScheduledModification>> SCHEDULED =
        new ConcurrentHashMap<>();

    private HealthModificationScheduler() {}

    /**
     * 注册一个周期性健康值修改任务。
     *
     * @param entity    目标实体
     * @param task      任务配置
     */
    public static void schedule(LivingEntity entity, ScheduledModification task) {
        SCHEDULED.computeIfAbsent(entity.getUUID(), k -> new ArrayList<>()).add(task);
    }

    /**
     * 取消指定实体的所有任务。
     */
    public static void removeAll(LivingEntity entity) {
        SCHEDULED.remove(entity.getUUID());
    }

    /**
     * 取消指定实体的特定任务。
     */
    public static void remove(LivingEntity entity, String taskId) {
        List<ScheduledModification> tasks = SCHEDULED.get(entity.getUUID());
        if (tasks != null) {
            tasks.removeIf(t -> t.taskId().equals(taskId));
            if (tasks.isEmpty()) {
                SCHEDULED.remove(entity.getUUID());
            }
        }
    }

    /**
     * 检查实体是否有注册的任务。
     */
    public static boolean hasScheduled(LivingEntity entity) {
        List<ScheduledModification> tasks = SCHEDULED.get(entity.getUUID());
        return tasks != null && !tasks.isEmpty();
    }

    /**
     * 每 tick 调用一次，检查并执行到期的任务。
     *
     * <p>快照迭代 + 现场列表为准：任务回调内允许 remove/schedule 自身或其它任务
     * （如持续压制的释放/升级），不会触发 ConcurrentModificationException，
     * 被移除的任务也不会被重新注册回列表。</p>
     */
    public static void tick(LivingEntity entity) {
        List<ScheduledModification> tasks = SCHEDULED.get(entity.getUUID());
        if (tasks == null || tasks.isEmpty()) return;

        List<ScheduledModification> snapshot = new ArrayList<>(tasks);
        for (ScheduledModification task : snapshot) {
            if (task.remainingTicks() <= 0) {
                // 任务到期，执行（回调内可能 remove 本任务或 schedule 新任务）
                task.modifier().apply(entity);
                if (task.repeat() && tasks.contains(task)) {
                    // 可重复任务重新调度（若回调内未移除）
                    tasks.add(new ScheduledModification(
                        task.taskId(), task.modifier(),
                        task.intervalTicks(), task.intervalTicks(), true
                    ));
                }
                // else: 一次性任务不重新加入
            } else if (tasks.contains(task)) {
                int idx = tasks.indexOf(task);
                tasks.set(idx, task.decrementTick());
            }
        }
    }

    // ==================== 任务记录 ====================

    /**
     * 寄存执行器 — 实际执行修改逻辑。
     */
    @FunctionalInterface
    public interface ModificationExecutor {
        void apply(LivingEntity entity);
    }

    /**
     * 定期修改任务记录（不可变）。
     *
     * @param taskId         任务唯一标识
     * @param modifier       执行器
     * @param remainingTicks 剩余 tick 数
     * @param intervalTicks  触发间隔（repeat=true 时使用）
     * @param repeat         是否重复执行
     */
    public record ScheduledModification(
        String taskId,
        ModificationExecutor modifier,
        int remainingTicks,
        int intervalTicks,
        boolean repeat
    ) {
        public ScheduledModification decrementTick() {
            // 只减到 0：可重复任务的重新武装由 tick() 的 run 分支完成。
            // 修复：旧实现 newRemaining<=0 && repeat 时直接重置回 interval，
            // 导致 repeating 任务 remaining 永远 ≥1、modifier 永不执行。
            return new ScheduledModification(
                taskId, modifier, Math.max(0, remainingTicks - 1), intervalTicks, repeat
            );
        }
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建一次性任务。
     *
     * @param taskId        任务标识
     * @param delayTicks    延迟刻数
     * @param modifier      执行器
     * @return 任务配置
     */
    public static ScheduledModification once(
        String taskId, int delayTicks, ModificationExecutor modifier
    ) {
        return new ScheduledModification(taskId, modifier, delayTicks, 0, false);
    }

    /**
     * 创建重复任务。
     *
     * @param taskId         任务标识
     * @param delayTicks     首次延迟刻数
     * @param intervalTicks  重复间隔刻数
     * @param modifier       执行器
     * @return 任务配置
     */
    public static ScheduledModification repeating(
        String taskId, int delayTicks, int intervalTicks, ModificationExecutor modifier
    ) {
        return new ScheduledModification(taskId, modifier, delayTicks, intervalTicks, true);
    }
}
