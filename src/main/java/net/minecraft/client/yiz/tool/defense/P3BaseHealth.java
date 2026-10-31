package net.minecraft.client.yiz.tool.defense;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/**
 * P3：基类生命通道直调（绕过子类对 {@code setHealth/getHealth/actuallyHurt} 的覆写）。
 *
 * <p>适用目标：自定义生命值实体、<code>setHealth</code> 内做静默截断、<code>getHealth</code> 返回伪装值、
 * 或 <code>actuallyHurt</code> 被重写的实体。</p>
 *
 * <p>做法（不经过目标类的 override）：</p>
 * <ol>
 *   <li>{@code IMPL_LOOKUP.findSpecial} 绑定 {@code LivingEntity} 的基类方法；</li>
 *   <li>按基类护甲/魔咒公式算实际伤害；</li>
 *   <li>调基类 {@code actuallyHurt}；</li>
 *   <li>若目标 {@code setHealth} 覆写把血量截断/拉高，再用基类 {@code setHealth} 写回
 *       {@code 命中前血量 - 实际伤害}，必要时用基类 {@code die} 收尾。</li>
 * </ol>
 *
 * <p>只在目标类确实覆写了生命方法时启用（{@link DefenseProfile#overrides}），避免影响普通实体。</p>
 */
public final class P3BaseHealth {

    private static final Logger LOGGER = LoggerFactory.getLogger("yizmodqzk-pohuan");

    private static final Unsafe U;
    private static final MethodHandle SET_HEALTH;
    private static final MethodHandle GET_HEALTH;
    private static final MethodHandle ACTUALLY_HURT;
    private static final MethodHandle BASE_HURT;
    private static final MethodHandle ARMOR_ABSORB;
    private static final MethodHandle MAGIC_ABSORB;
    private static final MethodHandle DIE;
    private static final boolean READY;

    static {
        Unsafe u = null;
        MethodHandle setHealth = null, getHealth = null, actuallyHurt = null, baseHurt = null, armor = null, magic = null, die = null;
        boolean ready = false;
        try {
            u = getUnsafe();
            MethodHandles.Lookup impl = implLookup(u);
            Class<LivingEntity> le = LivingEntity.class;
            java.lang.invoke.MethodType voidFloat = java.lang.invoke.MethodType.methodType(void.class, float.class);
            java.lang.invoke.MethodType floatVoid = java.lang.invoke.MethodType.methodType(float.class);
            java.lang.invoke.MethodType hurtType = java.lang.invoke.MethodType.methodType(void.class, DamageSource.class, float.class);
            java.lang.invoke.MethodType baseHurtType = java.lang.invoke.MethodType.methodType(boolean.class, DamageSource.class, float.class);
            java.lang.invoke.MethodType absorbType = java.lang.invoke.MethodType.methodType(float.class, DamageSource.class, float.class);
            java.lang.invoke.MethodType dieType = java.lang.invoke.MethodType.methodType(void.class, DamageSource.class);

            setHealth = findSpecial(impl, le, voidFloat, "setHealth", "m_21153_", le);
            getHealth = findSpecial(impl, le, floatVoid, "getHealth", "m_21223_", le);
            actuallyHurt = findSpecial(impl, le, hurtType, "actuallyHurt", "m_6475_", le);
            baseHurt = findSpecial(impl, le, baseHurtType, "hurt", "m_6469_", le);
            armor = findSpecial(impl, le, absorbType, "getDamageAfterArmorAbsorb", "m_21161_", le);
            magic = findSpecial(impl, le, absorbType, "getDamageAfterMagicAbsorb", "m_6515_", le);
            die = findSpecial(impl, le, dieType, "die", "m_6667_", le);
            ready = setHealth != null && actuallyHurt != null;
        } catch (Throwable t) {
            // 初始化失败则整体降级：调用方回退 actuallyHurt accessor
        }
        U = u;
        SET_HEALTH = setHealth;
        GET_HEALTH = getHealth;
        ACTUALLY_HURT = actuallyHurt;
        BASE_HURT = baseHurt;
        ARMOR_ABSORB = armor;
        MAGIC_ABSORB = magic;
        DIE = die;
        READY = ready;
    }

    private P3BaseHealth() {}

    public static boolean ready() {
        return READY;
    }

    /** 是否有基类 hurt 句柄（force_base_hurt 用）。 */
    public static boolean readyBaseHurt() {
        return BASE_HURT != null;
    }

    /**
     * 调基类 {@code LivingEntity.hurt}（跳过目标子类覆写，但保留本模组/原版 hurt 内全部
     * 伤害修正：攻击强度、暴击、护甲穿透、无敌帧判定等）。
     */
    public static boolean invokeBaseHurt(LivingEntity target, DamageSource source, float amount) {
        if (BASE_HURT == null || target == null || source == null) return false;
        float dmg = amount;
        if (!Float.isFinite(dmg) || dmg <= 0) return false;
        try {
            return (boolean) BASE_HURT.invoke(target, source, dmg);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable t) {
            net.minecraft.client.yiz.tool.safety.CrashGuard.report("pohuan-base-hurt", t);
            return false;
        }
    }

    /**
     * 用基类通道结算一次伤害；成功返回 true。
     * 失败时调用方应回退 {@code LivingEntityActuallyHurtAccessor}。
     */
    public static boolean settle(LivingEntity target, DamageSource source, float amount) {
        if (!READY || target == null || source == null) return false;
        float dmg = amount;
        if (!Float.isFinite(dmg) || dmg <= 0) return false;
        try {
            if (ARMOR_ABSORB != null) dmg = (float) ARMOR_ABSORB.invoke(target, source, dmg);
            if (MAGIC_ABSORB != null) dmg = (float) MAGIC_ABSORB.invoke(target, source, dmg);
            if (!Float.isFinite(dmg) || dmg <= 0) return false;

            float before = GET_HEALTH != null ? (float) GET_HEALTH.invoke(target) : target.getHealth();
            ACTUALLY_HURT.invoke(target, source, dmg);
            float expected = Math.max(0.0F, before - dmg);

            // 自定义 setHealth 把血拉高/截断：用基类 setHealth 写回应有值
            float after = GET_HEALTH != null ? (float) GET_HEALTH.invoke(target) : target.getHealth();
            if (expected > 0.0F && Float.isFinite(after) && after > expected + 0.01F) {
                SET_HEALTH.invoke(target, expected);
            } else if (expected <= 0.0F) {
                if (Float.isFinite(after) && after > 0.01F) {
                    SET_HEALTH.invoke(target, 0.0F);
                }
                if (!target.isDeadOrDying() && DIE != null) {
                    DIE.invoke(target, source);
                }
            }
            return true;
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable t) {
            net.minecraft.client.yiz.tool.safety.CrashGuard.report("pohuan-base-health", t);
            return false;
        }
    }

    // ==================== IMPL_LOOKUP / Unsafe ====================

    private static Unsafe getUnsafe() throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (Unsafe) f.get(null);
    }

    private static MethodHandles.Lookup implLookup(Unsafe u) throws Exception {
        Field f = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        long off = u.staticFieldOffset(f);
        return (MethodHandles.Lookup) u.getObject(u.staticFieldBase(f), off);
    }

    private static MethodHandle findSpecial(MethodHandles.Lookup impl, Class<?> owner,
                                            java.lang.invoke.MethodType type,
                                            String mcpName, String srgName, Class<?> specialCaller) {
        try {
            return impl.findSpecial(owner, srgName, type, specialCaller);
        } catch (Throwable ignored) {}
        try {
            return impl.findSpecial(owner, mcpName, type, specialCaller);
        } catch (Throwable ignored) {}
        return null;
    }
}
