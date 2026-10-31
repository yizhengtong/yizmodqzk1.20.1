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

    /** 已扫描过的类（每类一次）。 */
    private static final Set<Class<?>> SCANNED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger SCAN_LOGS = new AtomicInteger();

    /** 已打过的“拒绝伤害已改走 actuallyHurt”日志（每类一次）。 */
    private static final Set<Class<?>> SETTLE_LOGGED = ConcurrentHashMap.newKeySet();

    private PohuanXunzhen() {}

    // ==================== 属性门 ====================

    /** 攻击者是否持有生效中的破幻寻真（总值 &gt; 1）。 */
    public static boolean isActive(LivingEntity attacker) {
        if (attacker == null) return false;
        if (!YizConfig.bool("pohuan.enabled")) return false;
        try {
            AttributeInstance inst = attacker.getAttribute(YizAttributes.POHUAN_XUNZHEN.get());
            return inst != null && inst.getValue() > THRESHOLD;
        } catch (Throwable t) {
            return false;
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
        BREACH_TARGET.remove();
    }

    // ==================== 命中前 / 命中后 ====================

    /**
     * {@code LivingEntity.hurt} HEAD：清零无敌帧/硬直，并登记本次破除上下文。
     *
     * @return true 表示本次命中进入了破除上下文（调用方在 RETURN 需 endBreach）
     */
    public static boolean onHurtHead(LivingEntity attacker, LivingEntity target) {
        if (attacker == null || target == null) return false;
        if (attacker.level().isClientSide() || target.level().isClientSide()) return false;
        if (!isActive(attacker)) return false;
        if (!canAffect(target)) return false;

        beginBreach(target);
        if (YizConfig.bool("pohuan.zero_iframes")) {
            try {
                target.invulnerableTime = 0;
                target.hurtTime = 0;
            } catch (Throwable ignored) {}
        }
        scanOnce(target.getClass());
        return true;
    }

    /**
     * {@code LivingEntity.hurt} RETURN：若目标逻辑拒绝了本次伤害，改走 actuallyHurt 结算。
     *
     * @param success {@code hurt} 的返回值；true = 正常结算，不需要兜底
     */
    public static void onHurtReturn(LivingEntity target, DamageSource source, float amount, boolean success) {
        if (success || target == null || source == null) return;
        if (!YizConfig.bool("pohuan.settle_rejected")) return;
        if (!isBreaching(target)) return;
        if (target.isRemoved() || !target.isAlive()) return;
        settleRejected(target, source, amount);
    }

    /** 直接走 actuallyHurt 结算（已含护甲/魔咒减免，但跳过 hurt 的无敌/上限/自定义拒绝）。 */
    private static void settleRejected(LivingEntity target, DamageSource source, float amount) {
        float dmg = amount;
        if (!Float.isFinite(dmg) || dmg <= 0) return;
        try {
            ((LivingEntityActuallyHurtAccessor) target).yizmodqzk$actuallyHurt(source, dmg);
            if (SETTLE_LOGGED.add(target.getClass())) {
                LOGGER.info("[破幻寻真] {} 拒绝伤害 → actuallyHurt 结算 amount={}", target.getClass().getName(), dmg);
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable t) {
            CrashGuard.report("pohuan-settle", t);
        }
    }

    // ==================== 防御扫描（诊断 / 后续字节码阶段输入） ====================

    /**
     * 首次命中某类时扫描：列出被覆写的生命/伤害方法 + 名字像“上限/无敌帧”的数值字段。
     *
     * <p>只读，不改任何值。后续 P1 字节码阶段会以此 + ASM 数据流生成 DefenseProfile。</p>
     */
    private static void scanOnce(Class<?> cls) {
        if (cls == null) return;
        if (!YizConfig.bool("pohuan.scan_log")) return;
        if (!SCANNED.add(cls)) return;
        if (SCAN_LOGS.incrementAndGet() > 80) return;   // 防刷屏：最多 80 个类
        try {
            List<String> overrides = new ArrayList<>();
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (n.equals("hurt") || n.equals("actuallyHurt") || n.equals("setHealth")
                        || n.equals("getHealth") || n.equals("isInvulnerableTo")
                        || n.equals("die") || n.equals("isDeadOrDying") || n.equals("isAlive")) {
                    overrides.add(n);
                }
            }
            List<String> fields = new ArrayList<>();
            Class<?> c = cls;
            while (c != null && c != LivingEntity.class && fields.size() < 16) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t != int.class && t != long.class && t != float.class && t != double.class) continue;
                    String n = f.getName();
                    String ln = n.toLowerCase(java.util.Locale.ROOT);
                    if (ln.contains("invul") || ln.contains("iframe") || ln.contains("hurt")
                            || ln.contains("damage") || ln.contains("cap") || ln.contains("limit")
                            || ln.contains("cooldown") || ln.contains("tick")) {
                        fields.add(n + ":" + t.getSimpleName());
                        if (fields.size() >= 16) break;
                    }
                }
                c = c.getSuperclass();
            }
            LOGGER.info("[破幻寻真] 防御扫描 {}：覆写={} 候选字段={}", cls.getName(), overrides, fields);
        } catch (Throwable t) {
            // 扫描失败不影响破除
        }
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
