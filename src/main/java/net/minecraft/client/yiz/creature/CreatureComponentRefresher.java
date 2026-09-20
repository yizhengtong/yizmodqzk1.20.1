package net.minecraft.client.yiz.creature;

import net.minecraft.client.yiz.tool.effect.InstanceEffectState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 组件变更后的存量实体刷新器。
 *
 * <p>组件的常规生效时机是「实体首个服务端 tick」——这带来一个问题：数据包改了配置、
 * 或实例覆盖发生变化之后，场上已经存在的实体不会跟进，必须重新生成才生效。</p>
 *
 * <p>本类提供反向推动：变更发生后遍历当前世界中「有配置」的实体，重新应用一次原型的
 * 属性组件。血量按变更前后的最大生命比例保留，避免刷新把残血实体拉满或压死。</p>
 */
public final class CreatureComponentRefresher {

    private CreatureComponentRefresher() {}

    /**
     * 刷新全部维度中带有组件配置的实体。
     *
     * @return 被刷新的实体数量
     */
    public static int refreshAll(MinecraftServer server) {
        if (server == null) return 0;
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof LivingEntity living && hasConfig(living)) {
                    refresh(living);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 刷新单个实体：重新应用原型属性，血量按最大生命的比例保留。
     *
     * <p>只在最大生命确实变化时动血量；血量调整只走「治疗方向」（受保护实体的扣血方向由
     * 传导链管辖，直接写会被丢弃），因此刷新不会把实体压血。</p>
     */
    public static void refresh(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return;
        float maxBefore = entity.getMaxHealth();
        float healthBefore = entity.getHealth();
        CreatureProfileRegistry.apply(entity);
        float maxAfter = entity.getMaxHealth();
        if (maxBefore <= 0 || maxAfter <= 0 || Math.abs(maxAfter - maxBefore) < 0.001F) return;
        float target = maxAfter * (healthBefore / maxBefore);
        if (target > entity.getHealth()) {
            try {
                entity.setHealth(Math.min(target, maxAfter));
            } catch (Throwable ignored) {
                // 受保护实体拒绝外部写血时忽略：属性已经应用，血量交由自身机制收敛
            }
        }
    }

    /** 该实体是否带有组件配置（原型绑定或实例覆盖），没有配置的实体会被刷新跳过。 */
    public static boolean hasConfig(LivingEntity entity) {
        if (entity == null) return false;
        if (CreatureProfileRegistry.profileFor(entity) != null) return true;
        return !InstanceEffectState.patchOf(entity).isEmpty();
    }
}
