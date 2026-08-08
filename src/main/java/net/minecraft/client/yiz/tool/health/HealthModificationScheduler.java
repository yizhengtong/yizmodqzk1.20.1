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
     * @param entity 目标实体
     */
    public static void tick(LivingEntity entity) {
        List<ScheduledModification> tasks = SCHEDULED.get(entity.getUUID());
        if (tasks == null || tasks.isEmpty()) return;

        List<ScheduledModification> updated = new ArrayList<>();
        for (ScheduledModification task : tasks) {
            if (task.remainingTicks() <= 0) {
                // 任务到期，执行
                task.modifier().apply(entity);

                // 如果可重复，重新调度
                if (task.repeat()) {
                    updated.add(new ScheduledModification(
                        task.taskId(), task.modifier(),
                        task.intervalTicks(), task.intervalTicks(), true
                    ));
                }
                // else: 一次性任务，不加入 updated 列表
            } else {
                // 减少剩余 tick
                updated.add(task.decrementTick());
            }
        }
        SCHEDULED.put(entity.getUUID(), updated);
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
            int newRemaining = remainingTicks - 1;
            if (newRemaining <= 0 && repeat) {
                return new ScheduledModification(
                    taskId, modifier, intervalTicks, intervalTicks, true
                );
            }
            return new ScheduledModification(
                taskId, modifier, Math.max(0, newRemaining), intervalTicks, repeat
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
