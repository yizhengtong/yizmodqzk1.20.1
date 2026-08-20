package net.minecraft.client.yiz.tool.health;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用「死亡/移除放行开关」检测 + 击穿（拒死型实体门控）。
 *
 * <p>部分模组用<b>普通 boolean 实例字段</b>作死亡/移除放行开关：{@code die/remove/setRemoved}
 * 在方法开头检查它，为 false 时全部拦截/空转（外观上血打光也死不掉）。本类用纯行为验证识别
 * （不依赖字段名/类名/包名）：枚举实体自身层级的普通 boolean 字段，逐个「设 true → 试 die →
 * 观察是否真正进入死亡态 → 未命中还原」，命中即该 boolean 是放行开关。</p>
 *
 * <p><b>安全性</b>：只对「已抵抗正常死亡」的实体调用（主管道死亡链跑过、目标血 ≤0 但仍存活）。
 * 这类实体的 die 本就是被门控的空转，反复试 die 无副作用；正常实体不会走到这里。</p>
 */
public final class RemoveGateAccessor {

    private static final Map<String, Field> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> NEGATIVE = ConcurrentHashMap.newKeySet();

    private RemoveGateAccessor() {}

    /**
     * 检测并击穿目标实体的「死亡/移除放行开关」。命中则置 true 并触发一次 die()
     * （门已开，die 会走模组自己的掉落+移除），返回 true；无此类开关返回 false（不留副作用）。
     */
    public static boolean tamperToAllowDeath(LivingEntity entity, DamageSource source) {
        if (entity == null || entity.isRemoved() || entity.level().isClientSide()) return false;
        String key = entity.getClass().getName();
        Field gate = CACHE.get(key);
        if (gate == null && !NEGATIVE.contains(key)) {
            gate = doDetect(entity, source);
            if (gate != null) {
                CACHE.put(key, gate);
            } else {
                NEGATIVE.add(key);
            }
        }
        if (gate == null) return false;
        // 开门（幂等）并触发死亡：门已开则 die 直接走模组死亡链，触发掉落+移除
        try {
            gate.setAccessible(true);
            gate.setBoolean(entity, true);
        } catch (Throwable ignored) {}
        invokeDie(entity, source);
        return true;
    }

    // ==================== 检测 ====================

    private static Field doDetect(LivingEntity entity, DamageSource source) {
        try {
            List<Field> bools = new ArrayList<>();
            // 只扫 LivingEntity 之下的层级字段，避免把 vanilla 的 dead/health 等状态字段当候选
            for (Class<?> c = entity.getClass(); c != null && c != LivingEntity.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || Modifier.isFinal(m) || f.isSynthetic()) continue;
                    if (f.getType() != boolean.class) continue;
                    f.setAccessible(true);
                    bools.add(f);
                }
            }
            if (bools.isEmpty()) return null;
            // 基准：原字段状态下 die 若已经能进入死亡态，说明死被别的机制拦（非 boolean 门），放弃本类探测
            if (tryDie(entity, source)) return null;
            for (Field f : bools) {
                boolean original;
                try {
                    original = f.getBoolean(entity);
                } catch (Throwable t) {
                    continue;
                }
                if (original) continue;                       // 只测 false→true 的开门方向
                try {
                    f.setBoolean(entity, true);
                } catch (Throwable t) {
                    continue;
                }
                if (tryDie(entity, source)) {
                    return f;                                  // 开门成功：保持 true，实体已进死亡态
                }
                try {
                    f.setBoolean(entity, false);               // 未命中：还原，继续下一个
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 工具 ====================

    /** 调用 die()（双名匹配）并返回「是否真正进入死亡态」（dead 字段翻 true）。 */
    private static boolean tryDie(LivingEntity entity, DamageSource source) {
        try {
            invokeDie(entity, source);
            return readDead(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反射调用 LivingEntity.die（方法名 official + SRG 双名；Method.invoke 走虚分派命中 override）。 */
    private static void invokeDie(LivingEntity entity, DamageSource source) {
        try {
            Method m = null;
            try {
                m = LivingEntity.class.getDeclaredMethod("die", DamageSource.class);
            } catch (NoSuchMethodException e) {
                m = LivingEntity.class.getDeclaredMethod("m_6667_", DamageSource.class);
            }
            m.setAccessible(true);
            m.invoke(entity, source);
        } catch (Throwable ignored) {}
    }

    /** 读 vanilla dead 字段（official + SRG 双名）。 */
    private static boolean readDead(LivingEntity entity) {
        try {
            Field f = null;
            try {
                f = LivingEntity.class.getDeclaredField("dead");
            } catch (NoSuchFieldException e) {
                f = LivingEntity.class.getDeclaredField("f_20890_");
            }
            f.setAccessible(true);
            return f.getBoolean(entity);
        } catch (Throwable t) {
            return false;
        }
    }
}
