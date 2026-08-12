package net.minecraft.client.yiz.bridge;

/**
 * 实体 tick 追踪（强制双 tick 用）：记录实体被强制 tick 的状态，
 * 配合字节码注入实现"每 tick 跑两次"，同时防止 vanilla 普通 tick 与之冲突。
 */
public interface YizTickTracker {

    /** 上一次被强制 tick 时的 tickCount（与实体 tickCount 比较判重）。 */
    int yizmodqzk$getLastTickCount();

    void yizmodqzk$updateLastTickCount();

    /** 是否正在被强制 tick（防止强制 tick 内部重入）。 */
    boolean yizmodqzk$isUpdating();

    void yizmodqzk$markUpdating(boolean updating);
}
