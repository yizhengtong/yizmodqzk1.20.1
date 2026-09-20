package net.minecraft.client.yiz.creature;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 可派发的生物技能。
 *
 * <p>技能由 id 标识、从 {@link CombatSpec#skillId()} 选中，参数走 {@link CombatSpec#skillParams()}。
 * 实现注册在 {@link CreatureSkills}。</p>
 *
 * <p>生命周期分两段：{@link #start} 在选中技能的瞬间调用一次（做初始化），
 * 之后每 tick 调 {@link #tick}；{@link #tick} 返回 false 表示本次施放结束，实体可以再次选中。</p>
 *
 * <p>实例由注册表持有，必须无状态（所有运行期状态放在实体侧或由 {@link CreatureSkills} 维护），
 * 避免同一技能被多个实体同时使用时互相污染。</p>
 */
public interface CreatureSkill {

    /** 技能 id（与配置里的 {@code skill_id} 对应）。 */
    ResourceLocation id();

    /** 以该技能为前提的默认间隔下限（tick）；返回 0 表示不约束。 */
    default int defaultInterval() {
        return 0;
    }

    /**
     * 开始施放。返回 false 表示条件不满足、放弃本次施放。
     *
     * @param caster 施放者（服务端）
     * @param spec   触发时的战斗参数（含 skillParams）
     */
    default boolean start(LivingEntity caster, CombatSpec spec) {
        return true;
    }

    /**
     * 每 tick 驱动。
     *
     * @param elapsedTicks 自 {@link #start} 起经过的 tick 数
     * @return true 表示施放仍在进行，false 表示结束
     */
    default boolean tick(LivingEntity caster, CombatSpec spec, int elapsedTicks) {
        return false;
    }

    /** 施放中断（死亡、目标丢失等）。 */
    default void stop(LivingEntity caster) {
    }
}
