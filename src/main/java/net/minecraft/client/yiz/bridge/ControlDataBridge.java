package net.minecraft.client.yiz.bridge;

/**
 * 控制效果数据访问接口。
 * 由 {@link net.minecraft.client.yiz.mixin.LivingEntityMixin} 在 LivingEntity 上实现。
 */
public interface ControlDataBridge {

    /** 获取当前控制剩余 tick 数（任意类型 >0 即为被控制中）。 */
    int yizmodqzk$getControlTicks();

    /** 设置控制剩余 tick 数（客户端同步用）。 */
    void yizmodqzk$setControlTicks(int ticks);
}
