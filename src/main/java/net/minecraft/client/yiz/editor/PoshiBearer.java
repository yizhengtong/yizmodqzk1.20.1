package net.minecraft.client.yiz.editor;

/**
 * 破时携带者标记接口：实现此接口的实体（本模组 Boss 等）攻击时可触发破时——
 * 清目标无敌帧 + 绕过目标自定义 hurt 处理（配合 {@link PoshiBypassBridge}）。
 */
public interface PoshiBearer {
}
