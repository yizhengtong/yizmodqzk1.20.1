package net.minecraft.client.yiz.api;

import net.minecraft.world.entity.LivingEntity;

/**
 * 防御破除策略接口。
 *
 * <p>实现按“只改防御参数、不写生命值”的约束工作：无敌帧、受击硬直、
 * 无敌判定、伤害上限字段/常量、自定义生命覆写路径、死亡判定门。</p>
 *
 * <p>当前内置实现为 {@link PohuanXunzhen}；后续字节码扫描/改写策略按此接口扩展。</p>
 */
public interface DefenseBreach {

    /** 是否可以对该目标生效（属性等级、玩家游戏模式等由上层判定）。 */
    boolean supports(LivingEntity target);

    /** 命中前：把目标的防御参数临时归零/放宽。 */
    void neutralize(LivingEntity target);

    /** 还原（死亡/移除/关服/属性失效时）。 */
    void restore(LivingEntity target);
}
