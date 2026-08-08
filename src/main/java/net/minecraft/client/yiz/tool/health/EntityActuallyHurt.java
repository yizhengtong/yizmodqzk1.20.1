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
     * <p>⚠️ 1.20.1 注意：若反射失败回退 {@code setHealth}——辖界者 override 了 setHealth，
     * value 为其值时走"治疗方向写表"分支（不递归）。</p>
     */
    @SuppressWarnings("unchecked")
    public static void catchSetTrueHealth(LivingEntity living, float value) {
        if (!REFLECTION_AVAILABLE || HEALTH_FIELD == null) {
            living.setHealth(value);
            return;
        }
        try {
            HEALTH_FIELD.set(living, value);
            if (DATA_HEALTH_ID_FIELD != null) {
                EntityDataAccessor<Float> accessor =
                    (EntityDataAccessor<Float>) DATA_HEALTH_ID_FIELD.get(null);
                living.getEntityData().set(accessor, value);
            }
        } catch (Exception e) {
            living.setHealth(value);
        }
    }

    /** 未使用的签名占位（保持与 1.21.1 调用面一致，后续补 actuallyHurt）。 */
    public static void actuallyHurt(LivingEntity entity, DamageSource source, float amount) {
        entity.setHealth(Math.max(0, entity.getHealth() - amount));
    }
}
