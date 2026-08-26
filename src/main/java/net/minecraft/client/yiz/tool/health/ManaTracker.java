package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 蓝量追踪器 — 当前蓝量 + tick 回蓝（1.21.1 移植版）。
 *
 * <p>回蓝公式（每 tick）：
 * <pre>regen = MANA_REGEN × 0.05 + MAX_MANA × MANA_REGEN_PCT / 100 / 20</pre>
 * 蓝量不超过 MAX_MANA 上限。
 */
public final class ManaTracker {

    private static final ConcurrentHashMap<UUID, Float> MANA = new ConcurrentHashMap<>();

    private ManaTracker() {}

    /** 获取当前蓝量。不存在时返回上限值。 */
    public static float get(LivingEntity entity) {
        return MANA.getOrDefault(entity.getUUID(), getMax(entity));
    }

    /** 设置蓝量（不超上限、不低 0）。 */
    public static void set(LivingEntity entity, float value) {
        float max = getMax(entity);
        float clamped = Math.max(0f, Math.min(value, max));
        if (clamped <= 0) { MANA.remove(entity.getUUID()); return; }
        MANA.put(entity.getUUID(), clamped);
    }

    /** 增减蓝量（正=恢复，负=消耗）。返回实际变化量。 */
    public static float add(LivingEntity entity, float delta) {
        float cur = get(entity);
        float max = getMax(entity);
        float after = Math.max(0f, Math.min(cur + delta, max));
        set(entity, after);
        return after - cur;
    }

    /** 消耗蓝量，返回是否足够。 */
    public static boolean consume(LivingEntity entity, float amount) {
        float cur = get(entity);
        if (cur < amount) return false;
        set(entity, cur - amount);
        return true;
    }

    /** 读取 MAX_MANA 属性值作为上限。 */
    public static float getMax(LivingEntity entity) {
        var inst = entity.getAttribute(YizAttributes.MAX_MANA.get());
        return inst != null ? (float) inst.getValue() : 200f;
    }

    /** 每 tick 回蓝（由 tizMod 调用）。 */
    public static void tickRegen(LivingEntity entity) {
        float cur = get(entity);
        float max = getMax(entity);
        if (cur >= max) return;

        // 固定回蓝
        var regenInst = entity.getAttribute(YizAttributes.MANA_REGEN.get());
        float regen = regenInst != null ? (float) regenInst.getValue() * 0.05f : 0.05f;

        // 百分比回蓝
        var pctInst = entity.getAttribute(YizAttributes.MANA_REGEN_PCT.get());
        float pct = pctInst != null ? (float) pctInst.getValue() : 0f;
        if (pct > 0) regen += max * (pct / 100f / 20f);

        if (regen > 0) add(entity, regen);
    }

    /** 移除追踪。 */
    public static void remove(LivingEntity entity) {
        MANA.remove(entity.getUUID());
    }
}
