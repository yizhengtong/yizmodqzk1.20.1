package net.minecraft.client.yiz.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 锁定框供应者接口（1.21.1 移植版）。
 * 由效果或模组实现，向渲染器提供锁定目标 + 充能/就绪状态。
 * priority 越高越优先渲染。
 */
public interface TargetFrameProvider {

    /** 当前玩家锁定的目标实体；未锁定返回 null。 */
    Entity getTarget(Player player);

    /** 充能进度 [0,1]（渲染透明度）。 */
    float getCharge();

    /** 充能是否完成（满蓄力冻结旋转角）。 */
    boolean isReady();

    /** 渲染优先级，越高越优先。 */
    int getPriority();

    /** 自定义四角纹理（null = 使用默认纹理）。 */
    default ResourceLocation[] getCornerTextures() { return null; }
}
