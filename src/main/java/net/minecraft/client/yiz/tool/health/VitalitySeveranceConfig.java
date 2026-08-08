package net.minecraft.client.yiz.tool.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 禁疗配置（百分比 + 固定值）（1.20.1 移植版）。
 *
 * <p>从健康值根本禁止治疗。先百分比削减，再减固定值。</p>
 */
public final class VitalitySeveranceConfig {

    private static final Map<UUID, Config> BANS = new ConcurrentHashMap<>();

    private VitalitySeveranceConfig() {}

    /** 禁疗配置：percent 百分比(0~100)，fixedAmount 固定值(≥0)。 */
    public record Config(float percent, float fixedAmount) {
        public float apply(float healAmount) {
            float afterPercent = healAmount * (1.0f - Math.min(1.0f, percent / 100.0f));
            float result = afterPercent - Math.max(0, fixedAmount);
            return Math.max(0, result);
        }
    }

    public static void set(LivingEntity entity, float percent, float fixedAmount) {
        if (percent <= 0 && fixedAmount <= 0) {
            BANS.remove(entity.getUUID());
        } else {
            BANS.put(entity.getUUID(), new Config(
                Math.max(0, percent),
                Math.max(0, fixedAmount)
            ));
        }
    }

    public static Config get(LivingEntity entity) {
        return BANS.get(entity.getUUID());
    }

    public static void remove(LivingEntity entity) {
        BANS.remove(entity.getUUID());
    }

    public static void onEntityRemove(UUID uuid) {
        BANS.remove(uuid);
    }
}
