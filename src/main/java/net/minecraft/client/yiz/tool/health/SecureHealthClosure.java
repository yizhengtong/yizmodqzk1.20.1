package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实血量外部存储 — 1.20.1 移植版（参考 1.21.1 SecureHealthClosure）。
 *
 * <p><b>真实血量存外部哈希表</b>，实体 override {@code getHealth/setHealth/isAlive/isDeadOrDying}
 * 从表读/判定。外部模组调 {@code setHealth(0)} 时被实体 override 重定向到 {@code hurt()} 走传导限伤。</p>
 *
 * <p>⚠️ 1.20.1 网络差异：1.21.1 用 {@code PacketDistributor + S2CSecureHealthPayload} 广播客户端；
 * 1.20.1 需接入 SimpleChannel（网络层后置）。骨架阶段 {@link #setHealth} 只写本地表，
 * 客户端显示依赖后续 SimpleChannel 同步（S2C 包）。</p>
 */
public final class SecureHealthClosure {

    private SecureHealthClosure() {}

    private static final Map<UUID, Float> HEALTH_MAP = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> MAX_HEALTH_MAP = new ConcurrentHashMap<>();

    /** 是否启用血量外部存储（SECURE_PULSE > 0）。 */
    public static boolean isSecure(LivingEntity entity) {
        if (entity == null) return false;
        var inst = entity.getAttribute(YizAttributes.SECURE_PULSE.get());
        return inst != null && inst.getValue() > 0;
    }

    /** 读取逻辑血量（实体 override getHealth 用）。无记录 → maxHealth。 */
    public static float getHealth(LivingEntity entity) {
        Float v = HEALTH_MAP.get(entity.getUUID());
        return v != null ? v : entity.getMaxHealth();
    }

    /** 写逻辑血量（服务端写表；客户端显示依赖后续 S2C 同步）。 */
    public static void setHealth(LivingEntity entity, float value) {
        if (value < 0) value = 0;
        HEALTH_MAP.put(entity.getUUID(), value);
        // TODO(网络层): 1.20.1 用 SimpleChannel 广播 S2CSecureHealthPayload 同步客户端显示
    }

    /** 实体注册到外部存储（首次进入时）。 */
    public static void register(LivingEntity entity, float initialHp) {
        HEALTH_MAP.putIfAbsent(entity.getUUID(), initialHp);
    }

    /** 实体是否已在外部存储注册。 */
    public static boolean isRegistered(LivingEntity entity) {
        return entity != null && HEALTH_MAP.containsKey(entity.getUUID());
    }

    /**
     * 受保护最大生命值（防外部模组改 MAX_HEALTH 属性 modifier）。
     * 无记录 → 回退 vanilla 属性值（不用 entity.getMaxHealth()，防 override 递归）。
     */
    public static float getMaxHealth(LivingEntity entity) {
        Float v = MAX_HEALTH_MAP.get(entity.getUUID());
        if (v != null) return v;
        var inst = entity.getAttribute(Attributes.MAX_HEALTH);
        return inst != null ? (float) inst.getValue() : 20.0F;
    }

    /** 设置受保护最大生命值（applyEntityAttributes 难度缩放后调用）。 */
    public static void setMaxHealth(LivingEntity entity, float value) {
        MAX_HEALTH_MAP.put(entity.getUUID(), value);
    }

    /** 每 tick 清理死亡实体状态。 */
    public static void tick(LivingEntity entity) {
        if (!entity.isAlive()) removeAll(entity);
    }

    /** 实体死亡/移除时清理。 */
    public static void removeAll(LivingEntity entity) {
        HEALTH_MAP.remove(entity.getUUID());
        MAX_HEALTH_MAP.remove(entity.getUUID());
    }
}
