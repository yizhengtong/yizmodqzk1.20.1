package net.minecraft.client.yiz.tool.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 护盾值追踪器 — 动态消耗型额外血量。
 *
 * <p>受击时在格挡之后吸收伤害，护盾值不足时溢出到生命值。
 * 护盾值可被技能/物品直接增减（{@link #add}/{@link #set}）。</p>
 */
public final class ShieldTracker {

    private static final ConcurrentHashMap<UUID, Float> SHIELDS = new ConcurrentHashMap<>();

    private ShieldTracker() {}

    /** 获取当前护盾值。 */
    public static float get(LivingEntity entity) {
        return SHIELDS.getOrDefault(entity.getUUID(), 0f);
    }

    /** 直接设置护盾值（不超上限、不低 0）。 */
    public static void set(LivingEntity entity, float value) {
        if (value <= 0) { SHIELDS.remove(entity.getUUID()); return; }
        SHIELDS.put(entity.getUUID(), Math.max(0f, value));
    }

    /** 增减护盾值（正=恢复，负=消耗）。 */
    public static void add(LivingEntity entity, float delta) {
        if (delta == 0) return;
        float cur = get(entity);
        set(entity, cur + delta);
    }

    /** 移除追踪（实体死亡/卸载时清理）。 */
    public static void remove(LivingEntity entity) {
        SHIELDS.remove(entity.getUUID());
    }
}
