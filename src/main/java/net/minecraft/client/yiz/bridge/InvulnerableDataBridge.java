package net.minecraft.client.yiz.bridge;

/**
 * 玩家无敌数据访问接口。
 * 由 {@link net.minecraft.client.yiz.mixin.PlayerMixin} 在 Player 上实现。
 */
public interface InvulnerableDataBridge {

    /** 检查玩家是否处于无敌模式。 */
    boolean yizmodqzk$isInvulnerable();

    /** 设置玩家的无敌模式状态。 */
    void yizmodqzk$setInvulnerable(boolean invul);
}
