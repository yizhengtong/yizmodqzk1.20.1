package net.minecraft.client.yiz.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 {@link Level#getEntities()} 内部 {@link LevelEntityGetter}。
 *
 * <p>直接遍历世界实体集合（绕过 {@code EntityTypeTest} 过滤），比 {@code level.getEntitiesOfClass}
 * 更底层——扫到所有实体（含隐藏/特殊存储）。供涨跌多空攻击目标扫描、改血范围扫描等使用。</p>
 */
@Mixin(value = Level.class, priority = Integer.MAX_VALUE)
public interface YizLevelInvoker {

    @Invoker("getEntities")
    LevelEntityGetter<Entity> yizmodqzk$getLevelEntityGetter();
}
