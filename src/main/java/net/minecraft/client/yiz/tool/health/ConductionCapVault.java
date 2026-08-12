package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.tizMod;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传导限伤上限（CONDUCTION_CAP）权威值保险库 —— 「按属性值预算 + 调用检测」防外部模组篡改属性绕过限伤。
 *
 * <p>预算：受信任写入（{@link net.minecraft.client.yiz.tool.attribute.EntityAttributeGate#set}，
 * 编辑器/辖界者 setAttr 均走它）写 CONDUCTION_CAP 时登记权威值到此表。</p>
 *
 * <p>调用检测：{@link #checkAndRestore} 每 tick 由辖界者 {@code enforceSecureHealthState} 调用，
 * 对比属性当前值与权威表值——外部直接改属性（不走受信任 set）会不一致 → 判定篡改 →
 * 用权威值还原属性（受信任 set）+ 记日志。</p>
 *
 * <p>限伤计算（YizxianMob.conductionCap / ConductionDamageLimiter）读本表权威值，不读属性：
 * 外部改了属性也立即不影响限伤。</p>
 */
public final class ConductionCapVault {

    private ConductionCapVault() {}

    /** 权威限伤%（UUID → capPercent）。服务端维护；客户端不登记。 */
    private static final ConcurrentHashMap<UUID, Float> CAP_TABLE = new ConcurrentHashMap<>();

    /** 登记权威限伤%（受信任写入经 EntityAttributeGate.set 同步）。幂等覆盖。 */
    public static void register(LivingEntity entity, float capPercent) {
        if (entity == null || entity.level().isClientSide()) return;
        if (Float.isNaN(capPercent) || capPercent < 0) capPercent = 0;
        CAP_TABLE.put(entity.getUUID(), capPercent);
    }

    /** 读权威限伤%；未登记返回 null（调用方 fallback 属性值）。 */
    public static Float getPercent(LivingEntity entity) {
        if (entity == null) return null;
        return CAP_TABLE.get(entity.getUUID());
    }

    /** 实体移除/死亡清理。 */
    public static void remove(UUID uuid) {
        if (uuid != null) CAP_TABLE.remove(uuid);
    }

    /** 调用检测：属性值 vs 权威值，不一致=外部篡改 → 还原属性 + 日志。返回是否发生篡改还原。 */
    public static boolean checkAndRestore(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return false;
        Float authority = CAP_TABLE.get(entity.getUUID());
        if (authority == null) return false;
        var inst = entity.getAttribute(YizAttributes.CONDUCTION_CAP.get());
        if (inst == null) return false;
        double current = inst.getValue();
        if (Math.abs(current - authority) <= 1e-4) return false;

        // 编辑器合法编辑（markEdited）：属性是新值、权威表同步为新值，不还原（防把编辑器改的值当篡改）
        if (net.minecraft.client.yiz.tool.attribute.AttributeStandardizer.isEdited(entity, "conduction_cap")) {
            CAP_TABLE.put(entity.getUUID(), (float) current);
            return false;
        }

        // 篡改：外部直接改属性（base/非 prot_ modifier）绕过受信任 set → 还原权威值
        tizMod.LOGGER.warn("[CapGuard] conduction_cap 被外部篡改 {} -> {}（uuid={}）还原为权威值",
            current, authority, entity.getUUID());
        // 受信任 set（本包调用）写回权威值，EntityAttributeGate.set 会同步表值（同值幂等）
        net.minecraft.client.yiz.tool.attribute.EntityAttributeGate.set(
            entity, YizAttributes.CONDUCTION_CAP, "conduction_cap", authority);
        return true;
    }
}
