package net.minecraft.client.yiz.api;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.core.YizConfig;
import net.minecraft.client.yiz.mixin.LivingEntityActuallyHurtAccessor;
import net.minecraft.client.yiz.tool.safety.CrashGuard;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 【破幻寻真】防御破除（前置库接口实现）。
 *
 * <p>语义：属性 {@code yizmodqzk:pohuan_xunzhen} 的总值 {@code > 1.0} 时生效，
 * <b>全深度</b>破除目标防御；不写生命值，只改防御参数与判定路径。</p>
 *
 * <p>当前实现（P0/P1）：</p>
 * <ol>
 *   <li><b>无敌帧/受击硬直</b>：命中前清零 {@code invulnerableTime}/{@code hurtTime}；</li>
 *   <li><b>无敌判定</b>：本次 hurt 期间强制 {@code isInvulnerableTo} 返回 false
 *       （含生存玩家被套创造式无敌；创造/旁观模式玩家始终无效）；</li>
 *   <li><b>被拒绝的伤害</b>：目标 {@code hurt} 返回 false（无敌/免疫/自定义拒绝）时，
 *       改走基类 {@link LivingEntityActuallyHurtAccessor#yizmodqzk$actuallyHurt} 结算；</li>
 *   <li><b>防御扫描</b>：首次命中某类时反射列出被覆写的生命/伤害方法 + 候选上限字段，
 *       写入日志（供后续字节码扫描/改写阶段使用）。</li>
 * </ol>
 *
 * <p>注意：本类不做“改血”（不调用 setHealth、不写 DataItem），伤害仍由 hurt/actuallyHurt 链路结算。</p>
 */
public final class PohuanXunzhen implements DefenseBreach {

    public static final String ATTR_PATH = "pohuan_xunzhen";
    /** 生效阈值：总值必须严格大于该值。 */
    public static final double THRESHOLD = 1.0;

    private static final Logger LOGGER = LoggerFactory.getLogger("yizmodqzk-pohuan");

    /** 当前线程正在被“破幻寻真”命中结算的目标（供 isInvulnerableTo 判定）。 */
    private static final ThreadLocal<LivingEntity> BREACH_TARGET = new ThreadLocal<>();
    /** 命中前生命/吸收，用于识别“返回成功但静默无伤”的无敌。 */
    private static final ThreadLocal<Float> HEALTH_BEFORE = new ThreadLocal<>();
    private static final ThreadLocal<Float> ABSORPTION_BEFORE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> SETTLED = ThreadLocal.withInitial(() -> false);

    /** 已扫描过的类（每类一次）。 */
    private static final Set<Class<?>> SCANNED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger SCAN_LOGS = new AtomicInteger();

    /** 已打过的“拒绝伤害已改走 actuallyHurt”日志（每类一次）。 */
    private static final Set<Class<?>> SETTLE_LOGGED = ConcurrentHashMap.newKeySet();

    private PohuanXunzhen() {}

    // ==================== 属性门 ====================

    /** 攻击者是否持有生效中的破幻寻真（总值 &gt; 1）。 */
    public static boolean isActive(LivingEntity attacker) {
        return attacker != null && YizConfig.bool("pohuan.enabled") && rawValue(attacker) > THRESHOLD;
    }

    /** 属性总值（基础值 + 修饰符）；未挂载/异常返回 0。 */
    public static double rawValue(LivingEntity attacker) {
        if (attacker == null) return 0.0;
        try {
            AttributeInstance inst = attacker.getAttribute(YizAttributes.POHUAN_XUNZHEN.get());
            return inst != null ? inst.getValue() : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** 当前生效深度：本属性“全生效”，生效即 4；未生效 0。 */
    public static int depth(LivingEntity attacker) {
        return isActive(attacker) ? 4 : 0;
    }

    /**
     * 目标是否可被破除。
     *
     * <p>玩家目标只看游戏模式：创造/旁观 → 无效；生存/冒险 → 有效
     * （即使被套上 {@code abilities.invulnerable} 也可破，除非关闭开关）。</p>
     */
    public static boolean canAffect(LivingEntity target) {
        if (target == null || target.isRemoved()) return false;
        if (target instanceof Player p) {
            if (!YizConfig.bool("pohuan.affect_players")) return false;
            if (p.isCreative() || p.isSpectator()) return false;     // 只看游戏模式
            if (p.getAbilities().invulnerable && !YizConfig.bool("pohuan.break_creative_invuln")) {
                return false;
            }
            return true;
        }
        return true;
    }

    /** 本次 hurt 是否正处于“破幻寻真”结算上下文（供 isInvulnerableTo 注入使用）。 */
    public static boolean isBreaching(LivingEntity target) {
        return target != null && BREACH_TARGET.get() == target;
    }

    private static void beginBreach(LivingEntity target) {
        BREACH_TARGET.set(target);
    }

    /** 结束上下文；可重复调用。 */
    public static void endBreach() {
        try {
            if (net.minecraft.client.yiz.core.YizConfig.bool("pohuan.field_caps")) {
                net.minecraft.client.yiz.tool.defense.DefenseFieldBreaker.restore();
            }
        } catch (Throwable ignored) {}
        BREACH_TARGET.remove();
        HEALTH_BEFORE.remove();
        ABSORPTION_BEFORE.remove();
        SETTLED.remove();
    }

    // ==================== 命中前 / 命中后 ====================

    /** 诊断限频：前 30 次生效命中打印一次。 */
    private static final AtomicInteger HIT_LOG = new AtomicInteger();

    /**
     * {@code LivingEntity.hurt} HEAD：清零无敌帧/硬直，并登记本次破除上下文。
     *
     * @return true 表示本次命中进入了破除上下文（调用方在 RETURN 需 endBreach）
     */
    public static boolean onHurtHead(LivingEntity attacker, LivingEntity target) {
        if (attacker == null || target == null) return false;
        if (attacker.level().isClientSide() || target.level().isClientSide()) return false;

        double value = rawValue(attacker);
        boolean active = value > THRESHOLD;
        // 前 30 次命中无论是否生效都打一行：便于区分“属性没设上(total=0)”和“生效但没破防”
        if (HIT_LOG.incrementAndGet() <= 30) {
            LOGGER.info("[破幻寻真] 命中检查 target={} attacker={} total={} active={} canAffect={}",
                target.getClass().getSimpleName(), attacker.getClass().getSimpleName(),
                value, active, canAffect(target));
        }
        if (!active) return false;
        if (!canAffect(target)) return false;

        beginBreach(target);
        SETTLED.set(false);
        try {
            HEALTH_BEFORE.set(target.getHealth());
            ABSORPTION_BEFORE.set(target.getAbsorptionAmount());
        } catch (Throwable ignored) {}
        if (YizConfig.bool("pohuan.zero_iframes")) {
            try {
                target.invulnerableTime = 0;
                target.hurtTime = 0;
            } catch (Throwable ignored) {}
        }
        // 先扫描（首次命中建画像），再按画像做字段临时破除
        scanOnce(target.getClass());
        if (YizConfig.bool("pohuan.field_caps")) {
            try {
                net.minecraft.client.yiz.tool.defense.DefenseFieldBreaker.neutralize(target);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    /**
     * 是否应完全跳过目标自己的 {@code hurt}（强制走基类 actuallyHurt 结算）。
     *
     * <p>适用于 {@code hurt} 内用虚拟方法/状态机做限伤的实现（例如
     * {@code Math.min(DamageCap(), amount)}、距离衰减、damageBucket 累加器），
     * 这类上限既不是常量也不是字段，P2 改不到，只能绕过整个覆写。</p>
     */
    public static boolean shouldSkipHurt(LivingEntity target) {
        if (target == null || target instanceof Player) return false;
        if (!YizConfig.bool("pohuan.force_base_hurt")) return false;
        if (!net.minecraft.client.yiz.tool.defense.P3BaseHealth.ready()) return false;
        String name = target.getClass().getName();
        if (name.startsWith("net.minecraft.client.yiz")) return false;   // 不跳过自研实体
        net.minecraft.client.yiz.tool.defense.DefenseProfile p =
            net.minecraft.client.yiz.tool.defense.DefenseProfile.cached(name);
        return p != null && p.overrides != null
            && (p.overrides.contains("hurt") || p.overrides.contains("m_6469_"));
    }

    /** 外部（mixin HEAD 取消 hurt 时）直接结算：基类通道优先，失败回退 actuallyHurt。 */
    public static void settleDirect(LivingEntity target, DamageSource source, float amount) {
        SETTLED.set(false);
        settleRejected(target, source, amount);
    }

    /**
     * {@code LivingEntity.hurt} RETURN：处理两类“防御”。
     *
     * <ul>
     *   <li>返回 false（无敌/免疫/自定义拒绝）→ 改走 actuallyHurt；</li>
     *   <li>返回 true 但生命与吸收都没变化（静默无敌，常见于完全不看 tick 的状态机）
     *       → 同样改走 actuallyHurt。</li>
     * </ul>
     */
    public static void onHurtReturn(LivingEntity target, DamageSource source, float amount, boolean success) {
        if (target == null || source == null) return;
        if (!isBreaching(target)) return;
        if (target.isRemoved() || !target.isAlive()) return;
        if (SETTLED.get()) return;

        if (!success) {
            if (YizConfig.bool("pohuan.settle_rejected")) settleRejected(target, source, amount);
            return;
        }
        if (!YizConfig.bool("pohuan.settle_no_damage")) return;

        Float before = HEALTH_BEFORE.get();
        Float absBefore = ABSORPTION_BEFORE.get();
        if (before == null || !Float.isFinite(before)) return;
        try {
            float after = target.getHealth();
            float absAfter = target.getAbsorptionAmount();
            float abs0 = absBefore == null ? 0.0F : absBefore;
            // 生命没掉、吸收也没掉 = 本次伤害被静默吞掉（不区分是哪种无敌/状态机）
            if (Float.isFinite(after) && after >= before - 0.01F && absAfter >= abs0 - 0.01F) {
                settleRejected(target, source, amount);
            }
        } catch (Throwable ignored) {}
    }

    /** 直接走伤害结算：优先基类生命通道（P3，破自定义 setHealth 截断），否则 actuallyHurt accessor。 */
    private static void settleRejected(LivingEntity target, DamageSource source, float amount) {
        float dmg = amount;
        if (!Float.isFinite(dmg) || dmg <= 0) return;
        SETTLED.set(true);
        try {
            // P3：目标类覆写了 setHealth/getHealth/actuallyHurt → 走 IMPL_LOOKUP 基类通道
            net.minecraft.client.yiz.tool.defense.DefenseProfile p =
                net.minecraft.client.yiz.tool.defense.DefenseProfile.cached(target.getClass().getName());
            boolean customHealth = p != null && p.overrides != null
                && (p.overrides.contains("setHealth") || p.overrides.contains("m_21153_")
                    || p.overrides.contains("getHealth") || p.overrides.contains("m_21223_")
                    || p.overrides.contains("actuallyHurt") || p.overrides.contains("m_6475_"));
            if (customHealth
                    && YizConfig.bool("pohuan.base_health")
                    && net.minecraft.client.yiz.tool.defense.P3BaseHealth.settle(target, source, dmg)) {
                if (SETTLE_LOGGED.add(target.getClass())) {
                    LOGGER.info("[破幻寻真] 基类生命通道结算 amount={}（绕过自定义生命覆写）", dmg);
                }
                return;
            }
            ((LivingEntityActuallyHurtAccessor) target).yizmodqzk$actuallyHurt(source, dmg);
            if (SETTLE_LOGGED.add(target.getClass())) {
                LOGGER.info("[破幻寻真] 伤害被防御吞掉 → actuallyHurt 结算 amount={}", dmg);
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable t) {
            CrashGuard.report("pohuan-settle", t);
        }
    }

    // ==================== 防御扫描（诊断 / 后续字节码阶段输入） ====================

    /**
     * 首次命中某类时扫描（P1）：只读 ASM 画像 + 日志；P2b 开关打开时按类请求常量改写。
     *
     * <p>画像缓存到 {@code config/yizmodqzk/defense_profiles.json}，后续命中只读内存。</p>
     */
    private static void scanOnce(Class<?> cls) {
        if (cls == null) return;
        if (!YizConfig.bool("pohuan.scan_log") && !YizConfig.bool("pohuan.bytecode_patch")) return;
        if (!SCANNED.add(cls)) return;
        try {
            net.minecraft.client.yiz.tool.defense.DefenseProfile p =
                net.minecraft.client.yiz.tool.defense.DefenseScanner.ensureScanned(cls);
            if (p == null) return;
            if (YizConfig.bool("pohuan.scan_log") && SCAN_LOGS.incrementAndGet() <= 80) {
                StringBuilder caps = new StringBuilder();
                int n = 0;
                for (var c : p.caps) {
                    if (n++ >= 8) break;
                    caps.append(c.method).append(':').append(c.value).append(',');
                }
                StringBuilder fields = new StringBuilder();
                n = 0;
                for (var f : p.fields) {
                    if (n++ >= 8) break;
                    fields.append(f.name).append(',');
                }
                StringBuilder gates = new StringBuilder();
                n = 0;
                if (p.gates != null) {
                    for (var g : p.gates) {
                        if (n++ >= 12) break;
                        gates.append(g.name).append(':').append(g.kind).append(',');
                    }
                }
                LOGGER.info("[破幻寻真] 防御画像 {}：覆写={} 上限常量=[{}] 字段=[{}] 结构门=[{}]",
                    cls.getName(), p.overrides, caps, fields, gates);
            }
            if (YizConfig.bool("pohuan.bytecode_patch") && !p.caps.isEmpty()) {
                net.minecraft.client.yiz.tool.defense.DefensePatcher.requestPatch(cls);
            }
        } catch (Throwable ignored) {}
    }

    // ==================== DefenseBreach 接口 ====================

    @Override
    public boolean supports(LivingEntity target) {
        return canAffect(target);
    }

    @Override
    public void neutralize(LivingEntity target) {
        if (target == null) return;
        try {
            target.invulnerableTime = 0;
            target.hurtTime = 0;
        } catch (Throwable ignored) {}
    }

    @Override
    public void restore(LivingEntity target) {
        // 目前只做临时清零，无需还原；保留接口供 P1/P2（字段改写/字节码改写）使用。
    }
}
