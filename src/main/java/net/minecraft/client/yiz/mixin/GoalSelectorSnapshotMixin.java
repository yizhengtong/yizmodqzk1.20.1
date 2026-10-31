package net.minecraft.client.yiz.mixin;

import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

/**
 * 通用 GoalSelector 迭代加固（与任何具体模组无关）。
 *
 * <p>vanilla {@code GoalSelector.tickRunningGoals} 直接遍历内部的
 * {@code availableGoals}（LinkedHashSet）。任意模组/Goal 在 {@code Goal.tick()} 里
 * add/remove goal，都会让正在进行的遍历抛
 * {@code ConcurrentModificationException}，表现为 "Ticking entity" 崩溃。</p>
 *
 * <p>这里把迭代对象换成“遍历前快照”：
 * <ul>
 *   <li>不修改任何 goal、不改变 goal 的优先级/数量/数值；</li>
 *   <li>只是把“本 tick 开始时的 goal 列表”复制一份再遍历；</li>
 *   <li>代价是一次小数组拷贝（每实体每 tick 两次，goal 数通常个位数）；</li>
 *   <li>新增的 goal 下一 tick 生效，被移除的 goal 最多多 tick 一次，行为差异可忽略。</li>
 * </ul>
 * 这样无论第三方模组在 goal tick 里做什么，都不会再把服务端打崩。</p>
 */
@Mixin(value = GoalSelector.class, priority = 100)
public abstract class GoalSelectorSnapshotMixin {

    @Redirect(
        method = "tickRunningGoals",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;")
    )
    private Iterator<?> yizmodqzk$snapshotGoals(Set<?> goals) {
        // 快照迭代：goal 在 tick 内增删不会影响本次遍历
        return new ArrayList<>(goals).iterator();
    }
}
