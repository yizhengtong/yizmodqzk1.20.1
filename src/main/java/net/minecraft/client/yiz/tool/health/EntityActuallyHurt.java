package net.minecraft.client.yiz.tool.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

/**
 * 自定义伤害管道（1.20.1 移植版）。
 *
 * <p>仅移植辖界者用到的 {@link #catchSetTrueHealth}（每 tick 纠正被外部模组直写的血量字段）。
 * 完整伤害模式（actuallyHurt/Delta）后续按需补。</p>
 */
public final class EntityActuallyHurt {

    private EntityActuallyHurt() {}

    private static final VarHandle HEALTH_FIELD;
    private static final VarHandle DATA_HEALTH_ID_FIELD;
    private static final boolean REFLECTION_AVAILABLE;

    static {
        boolean ok = false;
        VarHandle healthField = null;
        VarHandle dataHealthIdField = null;

        try {
            Field f = LivingEntity.class.getDeclaredField("health");
            f.setAccessible(true);
            healthField = MethodHandles.lookup().unreflectVarHandle(f);

            Field d = LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
            d.setAccessible(true);
            dataHealthIdField = MethodHandles.lookup().unreflectVarHandle(d);

            ok = true;
        } catch (Exception e) {
            System.err.println("[yizmodqzk] EntityActuallyHurt reflection init failed: " + e.getMessage());
        }

        HEALTH_FIELD = healthField;
        DATA_HEALTH_ID_FIELD = dataHealthIdField;
        REFLECTION_AVAILABLE = ok;
    }

    /**
     * 强制设置实体的健康值，绕过 {@code setHealth()} 的所有逻辑。
     * 通过反射直接写 {@code health} 字段 + 更新 {@code DATA_HEALTH_ID} 同步客户端。
     *
     * <p>⚠️ 1.20.1 差异：{@code LivingEntity} <b>没有 {@code health} 普通字段</b>（血量在
     * {@code DATA_HEALTH_ID} DataParameter 通道）→ {@code getDeclaredField("health")} 抛
     * NoSuchFieldException → {@code REFLECTION_AVAILABLE=false}。此时<b>不能再回退
     * {@code setHealth}</b>（辖界者 override 黑洞，外部写不进）——改为直接写
     * {@code DATA_HEALTH_ID} DataItem（绕过 set() 限伤），确保"校正回表值"真正落到通道。</p>
     *
     * <p>优先级：① 有 health 字段（若未来版本恢复）→ 字段 + 通道双写；
     * ② 无 health 字段 → {@link DirectHealthFallback#setFloatChannelValue} 直写 vanilla 通道。</p>
     */
    @SuppressWarnings("unchecked")
    public static void catchSetTrueHealth(LivingEntity living, float value) {
        // 主路径：直接写 DATA_HEALTH_ID 通道（绕过 set() 与 setHealth override）
        if (DirectHealthFallback.VANILLA_HEALTH_ACCESSOR != null) {
            boolean wrote = DirectHealthFallback.setFloatChannelValue(
                living, DirectHealthFallback.VANILLA_HEALTH_ACCESSOR, value, true);
            if (wrote) return;
        }
        // 备选：有 health 字段 → 反射双写（字段 + 通道）
        if (REFLECTION_AVAILABLE && HEALTH_FIELD != null) {
            try {
                HEALTH_FIELD.set(living, value);
                if (DATA_HEALTH_ID_FIELD != null) {
                    EntityDataAccessor<Float> accessor =
                        (EntityDataAccessor<Float>) DATA_HEALTH_ID_FIELD.get(null);
                    living.getEntityData().set(accessor, value);
                }
                return;
            } catch (Exception e) {
                // 落空则继续回退
            }
        }
        // 最后兜底：setHealth（普通实体 override 未黑洞时有效）
        living.setHealth(value);
    }

    /** 未使用的签名占位（保持与 1.21.1 调用面一致，后续补 actuallyHurt）。 */
    public static void actuallyHurt(LivingEntity entity, DamageSource source, float amount) {
        entity.setHealth(Math.max(0, entity.getHealth() - amount));
    }
}
