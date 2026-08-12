package net.minecraft.client.yiz.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link ServerLevel#entityTickList}（私有字段）——强制双 tick 每 tick 更新实体状态用。
 * mixin @Accessor 由 refmap 自动映射字段名（dev official / 生产 SRG）。
 */
@Mixin(value = ServerLevel.class, priority = Integer.MAX_VALUE)
public interface YizServerLevelAccessor {

    @Accessor("entityTickList")
    EntityTickList yizmodqzk$getEntityTickList();
}
