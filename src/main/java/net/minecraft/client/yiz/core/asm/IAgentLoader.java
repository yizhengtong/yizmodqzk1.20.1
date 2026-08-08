package net.minecraft.client.yiz.core.asm;

/**
 * Agent 加载器接口（1.20.1 移植版，参考 yiz-main 同进程 self-attach 方案）。
 */
public interface IAgentLoader {

    /** Agent 是否已成功加载。 */
    boolean isLoaded();

    /** 执行 Agent 加载流程。 */
    void loadAgent();
}
