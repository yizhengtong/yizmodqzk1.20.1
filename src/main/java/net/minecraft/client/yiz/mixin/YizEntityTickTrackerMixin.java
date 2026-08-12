package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.bridge.YizTickTracker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 给所有实体挂 tick 追踪状态（强制双 tick 用）。@Unique 纯 Java 字段，无 vanilla 引用，安全。
 */
@Mixin(value = Entity.class, priority = Integer.MAX_VALUE)
public abstract class YizEntityTickTrackerMixin implements YizTickTracker {

    @Unique
    private int yizmodqzk$lastTickCount = -1;
    @Unique
    private boolean yizmodqzk$updating = false;

    @Override
    public int yizmodqzk$getLastTickCount() {
        return yizmodqzk$lastTickCount;
    }

    @Override
    public void yizmodqzk$updateLastTickCount() {
        this.yizmodqzk$lastTickCount = ((Entity) (Object) this).tickCount;
    }

    @Override
    public boolean yizmodqzk$isUpdating() {
        return yizmodqzk$updating;
    }

    @Override
    public void yizmodqzk$markUpdating(boolean updating) {
        this.yizmodqzk$updating = updating;
    }
}
