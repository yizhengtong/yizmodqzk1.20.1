package net.minecraft.client.yiz.bridge;

/**
 * 击飞飞行期的每 tick 维护回调。
 *
 * <p>击飞期间目标自身 tick 被停掉（AI/重力/摩擦/第三方每 tick 逻辑全停），
 * 但有些"每 tick 维护"是必须继续跑的——最典型的是混淆血量的对外显示同步
 * （下游 {@code YizxianMob.enforceSecureHealthState}：权威表 → 混淆串 → vanilla 通道），
 * 一旦停掉，被改血攻击打中后客户端血条就会和真实血量脱节。</p>
 *
 * <p>由 {@code LaunchController} 在驱动弹道时逐 tick 调用（实体实现了本接口才调）。</p>
 */
public interface LaunchTickBridge {

    /** 击飞飞行期每 tick 调用一次（仅服务端）。实现方需自行判客户端/空安全。 */
    void yizmodqzk$onLaunchTick();
}
