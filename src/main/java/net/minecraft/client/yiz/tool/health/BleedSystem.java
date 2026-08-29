package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.api.YizDamageTypes;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.effect.BleedEffects;
import net.minecraft.client.yiz.mixin.LivingEntityActuallyHurtAccessor;
import net.minecraft.client.yiz.mixin.MobEffectInstanceDurationAccessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraftforge.registries.RegistryObject;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 流血系统（服务端）：
 * <ul>
 *   <li>攻击者施加流血：读 {@code bleed_ratio}/{@code bleed_time}/{@code bleed_stack} 属性，
 *       未设置（≤0）的项用默认互补（比例 20%、时间 3 秒、叠加 2 次）；仅设比例也生效。</li>
 *   <li>流血期间目标被<b>外部伤害</b>实际扣血 → 按 {@code 扣血量 × 比例} 累加待结算流血伤害。</li>
 *   <li><b>持续结算</b>：待结算流血伤害分 <b>4 次</b>、每 4 tick 结算 1 次（总 16 tick），
 *       用流血伤害类型 {@code actuallyHurt} 直接结算（无视护甲/魔咒/抗性/无敌帧），不连锁。</li>
 *   <li>叠加：每次攻击 +1 层（上限叠加数），持续时间 = 每层时间 × 当前层数。</li>
 * </ul>
 */
public final class BleedSystem {

    private static final double DEFAULT_RATIO = 0.2;   // 默认流血比例 20%
    private static final int DEFAULT_TIME_SEC = 6;     // 默认每层时间 6 秒
    private static final int DEFAULT_STACK = 2;        // 默认最大叠加数 2
    private static final int TICKS_PER_SEC = 20;

    /** 持续结算：分 4 次、每 4 tick 一次（总 16 tick）。 */
    private static final int BLEED_PHASES = 4;
    private static final int TICK_PER_PHASE = 4;

    private static final Map<LivingEntity, BleedState> STATES = new IdentityHashMap<>();
    /** 防止流血伤害自身再触发流血反应（无限循环）。 */
    private static final ThreadLocal<Boolean> BLEED_ACTIVE = ThreadLocal.withInitial(() -> false);

    static final class BleedState {
        double ratio;
        int stacks;
        int remainTicks;
        float pendingBleed;   // 待结算的流血伤害
        int settlePhase;      // 已结算次数（0-3）
        int settleTick;       // 当前结算周期内 tick
    }

    private BleedSystem() {}

    /** 攻击者施加流血：读攻击者属性，未设置项用默认。比例 ≤0 不施加。 */
    public static void applyBleed(LivingEntity attacker, LivingEntity target) {
        if (target.level().isClientSide) return;
        double ratioPct = readAttr(attacker, YizAttributes.BLEED_RATIO);
        if (ratioPct <= 0) return;
        int timeSec = readAttr(attacker, YizAttributes.BLEED_TIME) > 0
            ? (int) readAttr(attacker, YizAttributes.BLEED_TIME) : DEFAULT_TIME_SEC;
        int maxStack = readAttr(attacker, YizAttributes.BLEED_STACK) > 0
            ? (int) readAttr(attacker, YizAttributes.BLEED_STACK) : DEFAULT_STACK;
        apply(target, ratioPct / 100.0, timeSec * TICKS_PER_SEC, maxStack);
    }

    /** 会心/渴攻蓄力满流血：比例按攻击者 bleed_ratio（配置优先，否则 30%）；时间/叠加固定默认（6秒/2次）。 */
    public static void applyChargeBleed(LivingEntity attacker, LivingEntity target) {
        if (target.level().isClientSide) return;
        double ratioPct = readAttr(attacker, YizAttributes.BLEED_RATIO);
        double ratio = ratioPct > 0 ? ratioPct / 100.0 : 0.3;
        apply(target, ratio, DEFAULT_TIME_SEC * TICKS_PER_SEC, DEFAULT_STACK);
    }

    private static void apply(LivingEntity target, double ratio, int timeTicks, int maxStack) {
        BleedState s = STATES.get(target);
        if (s == null) s = new BleedState();
        // 叠加：层数 +1，流血比例 × 层数（默认 20% 叠 2 层 = 40%），持续 = 每层时间 × 层数（6s × 2 = 12s）
        s.stacks = Math.min(s.stacks + 1, maxStack);
        s.ratio = ratio * s.stacks;
        s.remainTicks = timeTicks * s.stacks;
        STATES.put(target, s);
        syncBuff(target, s);
    }

    /** 同步前端展示 Buff：持续时间 = 流血剩余时间（纯展示，不驱动流血逻辑）。
     *  直接写 {@code MobEffectInstance.duration}（addEffect 的 update 只取更大的值，
     *  每 tick 递减的 remainTicks 无法通过 addEffect 刷新）。 */
    private static void syncBuff(LivingEntity target, BleedState s) {
        var effect = BleedEffects.BLEED.get();
        if (effect == null) return;
        MobEffectInstance inst = target.getEffect(effect);
        if (inst != null) {
            ((MobEffectInstanceDurationAccessor) inst).yizmodqzk$setDuration(Math.max(1, s.remainTicks));
        } else {
            target.addEffect(new MobEffectInstance(effect, Math.max(1, s.remainTicks), 0));
        }
    }

    /** 每 tick：结算待流血伤害（分 4 次）+ 递减状态时间（由 {@code LivingEntityBleedMixin.baseTick} 调用）。 */
    public static void tick(LivingEntity entity) {
        if (entity.level().isClientSide) return;
        BleedState s = STATES.get(entity);
        if (s == null) return;

        // 流血反应持续结算：分 4 次、每 4 tick 结算 1 次（总 16 tick）
        if (s.pendingBleed > 0.001f && s.settlePhase < BLEED_PHASES) {
            s.settleTick++;
            if (s.settleTick >= TICK_PER_PHASE) {
                float amount = s.pendingBleed / (BLEED_PHASES - s.settlePhase);
                s.settlePhase++;
                s.pendingBleed -= amount;
                applyBleedDamage(entity, amount);
                s.settleTick = 0;
            }
            if (s.settlePhase >= BLEED_PHASES) s.pendingBleed = 0;
        }

        // 流血状态时间递减 + 同步展示 Buff（结束时移除）
        if (s.remainTicks <= 1) {
            STATES.remove(entity);
            var effect = BleedEffects.BLEED.get();
            if (effect != null) entity.removeEffect(effect);
        } else {
            s.remainTicks--;
            syncBuff(entity, s);
        }
    }

    /** 外部伤害反应：目标被外部伤害实际扣血 → 累加待结算流血伤害（持续结算，防连锁）。 */
    public static void onExternalDamage(LivingEntity target, float actualDamage) {
        if (target.level().isClientSide) return;
        if (BLEED_ACTIVE.get()) return;
        BleedState s = STATES.get(target);
        if (s == null || actualDamage <= 0.001f) return;
        float bleed = (float) (actualDamage * s.ratio);
        if (bleed <= 0.001f) return;
        // 累加待结算 + 重置结算计划（分 4 次 × 16 tick）
        s.pendingBleed += bleed;
        s.settlePhase = 0;
        s.settleTick = 0;
    }

    /** 单次流血伤害结算（流血伤害类型，无视所有减免）。 */
    private static void applyBleedDamage(LivingEntity target, float amount) {
        if (amount <= 0.001f) return;
        BLEED_ACTIVE.set(true);
        try {
            ((LivingEntityActuallyHurtAccessor) target).yizmodqzk$actuallyHurt(bleedSource(target), amount);
        } finally {
            BLEED_ACTIVE.set(false);
        }
        // actuallyHurt 不走 hurt()，手动补受击反馈（受击动画）
        target.hurtTime = 10;
        target.hurtDuration = 10;
    }

    /** 流血伤害源（yizmodqzk:bleed DamageType）。 */
    private static DamageSource bleedSource(LivingEntity target) {
        var reg = target.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        var holder = reg.getHolder(YizDamageTypes.BLEED);
        return holder.map(DamageSource::new).orElse(target.damageSources().generic());
    }

    /** 当前是否有流血状态（供 UI/其他效果查询）。 */
    public static boolean isBleeding(LivingEntity entity) {
        return STATES.containsKey(entity);
    }

    private static double readAttr(LivingEntity entity, RegistryObject<Attribute> attr) {
        if (attr == null || !attr.isPresent()) return 0.0;
        var inst = entity.getAttribute(attr.get());
        return inst != null ? inst.getValue() : 0.0;
    }
}
