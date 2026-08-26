package net.minecraft.client.yiz.core;

import net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType;
import net.minecraft.client.yiz.api.YizModQZKAPI;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.bridge.ControlDataBridge;
import net.minecraft.client.yiz.network.NetworkHandler;
import net.minecraft.client.yiz.network.S2CShockFxPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 状态效果分发器 —— 控制效果 + 伤害的完整实现（1.21.1 全量版移植，D3：S2C 视觉段 no-op）。
 *
 * <p>1.20.1 适配点：
 * <ul>
 *   <li>{@code Holder<Attribute>} → {@code Attribute}（静态初始化用 {@code YizAttributes.XXX.get()}）</li>
 *   <li>SLOW modifier id：{@code ResourceLocation} → 确定性 {@link UUID}（1.20.1 无 ResourceLocation id 构造）</li>
 *   <li>D3：{@link #dispatchChainFx} 的视觉包发送段 no-op（1.20.1 尚无 SimpleChannel，阶段6 接入
 *       S2CShockFxPayload；ShockState 逻辑 + 伤害保留）</li>
 * </ul></p>
 */
public final class StatusEffectDispatcher {

    // ══════════════════════════════════════════════════════════════
    //  默认值
    // ══════════════════════════════════════════════════════════════

    static final float DEFAULT_TIME = 40f;
    static final float DEFAULT_DAMAGE = 2f;

    /** 防递归：状态伤害造成的 hurt() 不再次触发攻方派发 */
    public static final ThreadLocal<Boolean> DISPATCHING = ThreadLocal.withInitial(() -> false);

    // ══════════════════════════════════════════════════════════════
    //  逐实体控制计时器（服务端）
    // ══════════════════════════════════════════════════════════════

    /** UUID → 各类型剩余 tick */
    private static final Map<UUID, EnumMap<StatusEffectType, Integer>> CONTROL_TIMERS = new java.util.concurrent.ConcurrentHashMap<>();

    /** UUID → 击飞每 tick 上升速度（含重力补偿） */
    private static final Map<UUID, Float> KNOCKBACK_LIFT = new java.util.concurrent.ConcurrentHashMap<>();

    /** 感电源头：UUID → [剩余tick, 单次伤害, 范围, 间隔, 原始来源, 吸血比例]。仅被直接命中的目标为源头，不传播。 */
    private static final Map<UUID, ShockState> SHOCK_STATES = new java.util.concurrent.ConcurrentHashMap<>();
    private record ShockState(int remaining, float dmg, float range, int interval,
                              LivingEntity source, float lifesteal, int maxTargets) {}

    /** 减速 modifier id（1.20.1 用确定性 UUID） */
    private static final UUID SLOW_MODIFIER_ID =
        UUID.nameUUIDFromBytes("yizmodqzk:status_slow".getBytes(StandardCharsets.UTF_8));

    // ══════════════════════════════════════════════════════════════
    //  时间/伤害属性映射
    // ══════════════════════════════════════════════════════════════

    private static final Map<StatusEffectType, Attribute> TIME_ATTRS  = new EnumMap<>(StatusEffectType.class);
    private static final Map<StatusEffectType, Attribute> DAMAGE_ATTRS = new EnumMap<>(StatusEffectType.class);

    static {
        TIME_ATTRS.put(StatusEffectType.STUN,      YizAttributes.STUN_TIME.get());
        TIME_ATTRS.put(StatusEffectType.SLOW,      YizAttributes.SLOW_TIME.get());
        TIME_ATTRS.put(StatusEffectType.FREEZE,    YizAttributes.FREEZE_TIME.get());
        TIME_ATTRS.put(StatusEffectType.SHOCK,     YizAttributes.SHOCK_TIME.get());
        TIME_ATTRS.put(StatusEffectType.KNOCKBACK, YizAttributes.KNOCKBACK_TIME.get());

        DAMAGE_ATTRS.put(StatusEffectType.STUN,      YizAttributes.STUN_DAMAGE.get());
        DAMAGE_ATTRS.put(StatusEffectType.SLOW,      YizAttributes.SLOW_DAMAGE.get());
        DAMAGE_ATTRS.put(StatusEffectType.FREEZE,    YizAttributes.FREEZE_DAMAGE.get());
        DAMAGE_ATTRS.put(StatusEffectType.SHOCK,     YizAttributes.SHOCK_DAMAGE.get());
        DAMAGE_ATTRS.put(StatusEffectType.KNOCKBACK, YizAttributes.KNOCKBACK_DAMAGE.get());
    }

    private StatusEffectDispatcher() {}

    // ══════════════════════════════════════════════════════════════
    //  公开 API
    // ══════════════════════════════════════════════════════════════

    public static void dispatchToTarget(LivingEntity target, Map<StatusEffectType, Float> effects,
                                         LivingEntity source) {
        if (DISPATCHING.get()) return; // 状态伤害造成的 hurt 不再次派发（防递归）
        System.out.println("[StatusEffect] dispatchToTarget target=" + target + " effects=" + effects + " source=" + source);
        for (var entry : effects.entrySet()) {
            float chance = entry.getValue();
            if (chance <= 0 || Math.random() * 100.0 >= chance) continue;
            applyEffect(entry.getKey(), target, source);
        }
    }

    public static void dispatchToAttacker(LivingEntity attacker, Map<StatusEffectType, Float> effects,
                                           LivingEntity defender) {
        if (DISPATCHING.get()) return; // 状态伤害造成的 hurt 不再次派发（防递归）
        for (var entry : effects.entrySet()) {
            float chance = entry.getValue();
            if (chance <= 0 || Math.random() * 100.0 >= chance) continue;
            applyEffect(entry.getKey(), attacker, defender);
        }
    }

    /** 公开入口：直接对目标施加指定状态效果（无概率掷骰，100%）。供技能等确定性触发用。 */
    public static void applyDirect(StatusEffectType type, LivingEntity target, LivingEntity source) {
        applyEffect(type, target, source);
    }

    // ══════════════════════════════════════════════════════════════
    //  Tick 处理（由 LivingEntityMixin 调用）
    // ══════════════════════════════════════════════════════════════

    public static void tickControlTimers(LivingEntity entity) {
        if (entity instanceof ControlDataBridge bridge) {
            UUID uuid = entity.getUUID();
            var timers = CONTROL_TIMERS.get(uuid);

            // 减速到期检查
            checkSlowExpiry(entity, timers);

            // 感电：被标记目标为唯一源头，每隔 interval tick 向周围造成范围伤害
            ShockState shock = SHOCK_STATES.get(uuid);
            if (shock != null) {
                int newRemaining = shock.remaining() - 1;
                int iv = Math.max(1, shock.interval());
                // 每到间隔点：以当前实体为源头对周围打一次范围伤害
                if (newRemaining >= 0 && newRemaining % iv == 0) {
                    doShockAoE(entity, shock);
                }
                if (newRemaining <= 0) {
                    SHOCK_STATES.remove(uuid);
                } else {
                    SHOCK_STATES.put(uuid, new ShockState(newRemaining, shock.dmg(), shock.range(),
                        iv, shock.source(), shock.lifesteal(), shock.maxTargets()));
                }
            }

            if (timers == null || timers.isEmpty()) {
                // 击飞 lift 清理
                KNOCKBACK_LIFT.remove(uuid);
                if (bridge.yizmodqzk$getControlTicks() != 0) {
                    bridge.yizmodqzk$setControlTicks(0);
                    onControlEnd(entity);
                }
                return;
            }

            // 所有类型各减 1
            int maxRemaining = 0;
            var iter = timers.entrySet().iterator();
            while (iter.hasNext()) {
                var e = iter.next();
                StatusEffectType type = e.getKey();
                int remaining = e.getValue() - 1;
                if (remaining <= 0) {
                    iter.remove();
                    onTypeExpire(entity, type);
                } else {
                    e.setValue(remaining);
                    if (remaining > maxRemaining) maxRemaining = remaining;
                }
            }

            // 击飞：每 tick 施加上升速度
            Float lift = KNOCKBACK_LIFT.get(uuid);
            if (lift != null && timers.containsKey(StatusEffectType.KNOCKBACK)) {
                entity.setDeltaMovement(entity.getDeltaMovement().x, lift, entity.getDeltaMovement().z);
            }

            if (timers.isEmpty()) {
                CONTROL_TIMERS.remove(uuid);
                KNOCKBACK_LIFT.remove(uuid);
            }
            bridge.yizmodqzk$setControlTicks(maxRemaining);
            if (maxRemaining == 0) onControlEnd(entity);
        }
    }

    /** 某类型过期时的清理 */
    private static void onTypeExpire(LivingEntity entity, StatusEffectType type) {
        if (type == StatusEffectType.FREEZE) {
            entity.setTicksFrozen(0); // 清除冰冻视觉
        }
    }

    /** 所有控制结束时 */
    private static void onControlEnd(LivingEntity entity) {
        entity.setTicksFrozen(0);
        // 减速在 checkSlowExpiry 中独立处理，不受控制结束影响
    }

    // ══════════════════════════════════════════════════════════════
    //  效果应用
    // ══════════════════════════════════════════════════════════════

    private static void applyEffect(StatusEffectType type, LivingEntity target,
                                     @Nullable LivingEntity source) {
        if (source == null) return;

        switch (type) {
            case STUN      -> applyStun(target, source);
            case FREEZE    -> applyFreeze(target, source);
            case KNOCKBACK -> applyKnockback(target, source);
            case SLOW      -> applySlow(target, source);
            case SHOCK     -> applyShock(target, source);
        }
    }

    // ── 眩晕 ────────────────────────────────────────────────────

    private static void applyStun(LivingEntity target, LivingEntity source) {
        int time = (int) readTimeAttr(source, StatusEffectType.STUN);
        float damage = readDamageAttr(source, StatusEffectType.STUN);
        addControlTime(target, StatusEffectType.STUN, time);
        if (damage > 0) dealStatusDamage(target, StatusEffectType.STUN, damage, source);
    }

    // ── 冰冻 ────────────────────────────────────────────────────

    private static void applyFreeze(LivingEntity target, LivingEntity source) {
        int time = (int) readTimeAttr(source, StatusEffectType.FREEZE);
        float damage = readDamageAttr(source, StatusEffectType.FREEZE);
        addControlTime(target, StatusEffectType.FREEZE, time);
        target.setTicksFrozen(target.getTicksRequiredToFreeze()); // 满冰冻视觉
        if (damage > 0) dealStatusDamage(target, StatusEffectType.FREEZE, damage, source);
    }

    // ── 击飞（高度固定 4 格，时长由 knockback_time 控制）─────────────

    /** 击飞最大高度（格） */
    private static final float KNOCKBACK_MAX_HEIGHT = 4f;
    /** 重力加速度（格/tick²），与 Minecraft 原版一致 */
    private static final float GRAVITY = 0.08f;

    private static void applyKnockback(LivingEntity target, LivingEntity source) {
        float timeAttr = readTimeAttr(source, StatusEffectType.KNOCKBACK);
        float damage = readDamageAttr(source, StatusEffectType.KNOCKBACK);
        int time = Math.max((int) timeAttr, 5);

        // 每 tick 上升速度 = 高度/时长 + 重力补偿
        // 补偿重力是因为 travel 每 tick 会从 vy 减去 0.08
        float liftVelocity = KNOCKBACK_MAX_HEIGHT / time + GRAVITY;
        KNOCKBACK_LIFT.put(target.getUUID(), liftVelocity);

        target.hurtMarked = true;
        addControlTime(target, StatusEffectType.KNOCKBACK, time);
        if (damage > 0) dealStatusDamage(target, StatusEffectType.KNOCKBACK, damage, source);
    }

    // ── 减速 ────────────────────────────────────────────────────

    private static void applySlow(LivingEntity target, LivingEntity source) {
        float slowPct = readTimeAttr(source, StatusEffectType.SLOW); // slow_time 即减速%
        float damage = readDamageAttr(source, StatusEffectType.SLOW);
        if (slowPct <= 0) slowPct = DEFAULT_TIME;

        // 限制在 0-100%
        slowPct = Math.min(100f, Math.max(0f, slowPct));

        var attr = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) return;

        // 移除旧 modifier
        attr.removeModifier(SLOW_MODIFIER_ID);
        if (slowPct > 0) {
            double reduction = attr.getBaseValue() * (slowPct / 100.0);
            attr.addPermanentModifier(
                new AttributeModifier(SLOW_MODIFIER_ID, "yizmodqzk:status_slow", -reduction, AttributeModifier.Operation.ADDITION));

            // 记录到期时间
            UUID uuid = target.getUUID();
            var timers = CONTROL_TIMERS.computeIfAbsent(uuid, k -> new EnumMap<>(StatusEffectType.class));
            // 减速在 CONTROL_TIMERS 中存到期 tick 数的负值（不影响硬控 max 计算）
            // 实际上直接设置 SLOW 类型 timer（不参与 CONTROL_TICKS max 是因为 SLOW 不阻止 AI）
        }

        // 减速也设置一个短计时器用于到期检查（不阻止 AI，仅用于 modifier 清理）
        // 用独立的标记
        addSlowTimer(target, (int) slowPct);

        if (damage > 0) dealStatusDamage(target, StatusEffectType.SLOW, damage, source);
    }

    /** 减速计时器（独立于硬控 CONTROL_TIMERS，因为减速不阻止 AI） */
    private static final Map<UUID, Integer> SLOW_EXPIRY = new java.util.concurrent.ConcurrentHashMap<>();

    private static void addSlowTimer(LivingEntity target, int durationTicks) {
        UUID uuid = target.getUUID();
        Integer existing = SLOW_EXPIRY.get(uuid);
        int newVal = existing != null ? Math.max(existing, durationTicks) : durationTicks;
        SLOW_EXPIRY.put(uuid, newVal);
    }

    private static void checkSlowExpiry(LivingEntity entity, @Nullable Map<StatusEffectType, Integer> timers) {
        UUID uuid = entity.getUUID();
        Integer remaining = SLOW_EXPIRY.get(uuid);
        if (remaining == null) return;

        int newRemaining = remaining - 1;
        if (newRemaining <= 0) {
            SLOW_EXPIRY.remove(uuid);
            var attr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attr != null) attr.removeModifier(SLOW_MODIFIER_ID);
        } else {
            SLOW_EXPIRY.put(uuid, newRemaining);
        }
    }

    /**
     * 仅施加感电视觉效果（中心体表电流 + 链式闪电），不造成伤害、不注册 SHOCK_STATES。
     * 供技能释放时给施法者自身叠加闪电视觉。D3：视觉发送 no-op。
     */
    public static void applyShockVisualOnly(LivingEntity center, float range, int durationTicks) {
        if (center.level().isClientSide()) return;
        dispatchChainFx(center, center, range, durationTicks, readShockCount(center));
    }

    // ── 感电（唯一源头=被命中目标，活着就周期性对周围造成范围伤害，不传播）──

    private static void applyShock(LivingEntity target, LivingEntity source) {
        float time = readTimeAttr(source, StatusEffectType.SHOCK);
        float dmg = readDamageAttr(source, StatusEffectType.SHOCK);
        float range = readShockRange(source);
        int interval = readShockInterval(source);
        applyShock(target, source, dmg, range, time, interval, 0f);
    }

    /**
     * 用指定参数施加感电（供技能/被动等需要自定义伤害与范围的入口）。
     * {@code dmg/range/time/interval} 传 ≤0 时回退到 source 属性或默认值。
     */
    public static void applyShockWithDamage(LivingEntity target, LivingEntity source,
                                            float dmg, float range, float time, int interval) {
        applyShockWithDamage(target, source, dmg, range, time, interval, 0f);
    }

    /** 带吸血比例的感电施加。lifesteal: 0~1，AoE 伤害的该比例转化为对 source 的治疗。 */
    public static void applyShockWithDamage(LivingEntity target, LivingEntity source,
                                            float dmg, float range, float time, int interval,
                                            float lifesteal) {
        if (dmg <= 0) dmg = readDamageAttr(source, StatusEffectType.SHOCK);
        if (range <= 0) range = readShockRange(source);
        if (time <= 0) time = readTimeAttr(source, StatusEffectType.SHOCK);
        if (interval <= 0) interval = readShockInterval(source);
        applyShock(target, source, dmg, range, time, interval, lifesteal);
    }

    private static void applyShock(LivingEntity target, LivingEntity source,
                                   float dmg, float range, float time, int interval,
                                   float lifesteal) {
        if (time <= 0) time = DEFAULT_TIME;
        if (range <= 0) range = 2.5f;
        if (interval <= 0) interval = 10;

        // 命中即承受标准配置伤害（标记目标至少吃这一下）；免疫自伤（source==target，如玩家技能自施）
        if (target != source) {
            DISPATCHING.set(true);
            try {
                YizModQZKAPI.armorPiercingAndPierceInvulnerabilityDamage(target, dmg, source);
                // 吸血：初始伤害按比例治疗 source
                if (lifesteal > 0 && source.isAlive()) {
                    source.heal(dmg * lifesteal);
                }
            } finally {
                DISPATCHING.set(false);
            }
        }

        // 标记目标为感电唯一源头：仅它活着时会周期性向周围造成范围伤害，其余实体不传播
        UUID uuid = target.getUUID();
        ShockState existing = SHOCK_STATES.get(uuid);
        int newTime = existing != null ? Math.max(existing.remaining(), (int) time) : (int) time;
        int maxTargets = readShockCount(source);
        SHOCK_STATES.put(uuid, new ShockState(newTime, dmg, range, interval, source, lifesteal, maxTargets));

        // 感电视觉：以 target 为中心（含自身体表电流），向周围实体发链式闪电
        dispatchChainFx(target, source, range, 20, maxTargets);
    }

    /**
     * 视觉包分发（D3：1.20.1 尚无 SimpleChannel，发送段 no-op；阶段6 接入 S2CShockFxPayload）。
     * <p>
     * 保留结构：收集附近实体 + 位置去重 + 链式最近邻排序（kind=1 chainIds）。发送段注释。
     * </p>
     */
    private static void dispatchChainFx(LivingEntity center, LivingEntity source, float range,
                                        int burstTicks, int maxTargets) {
        if (!(center.level() instanceof ServerLevel sl)) return;

        double radius = Math.max(range, 1.0);
        AABB aabb = center.getBoundingBox().inflate(radius);
        List<LivingEntity> nearby = center.level().getEntitiesOfClass(
            LivingEntity.class, aabb,
            e -> e.isAlive() && e != center && e != source && center.distanceToSqr(e) <= radius * radius);

        // 中心体表电流（kind=0）+ 周围实体体表电流（kind=0，位置去重，同格只发一份）
        java.util.Set<Long> seenCells = new java.util.HashSet<>();
        List<LivingEntity> deduped = new ArrayList<>();
        for (LivingEntity e : nearby) {
            if (!seenCells.add(cellKey(e))) continue;
            deduped.add(e);
        }
        // 按距离排序：上限截断取「最近」的实体，而非 level 存储顺序（否则会连到远方实体）
        List<LivingEntity> sorted = new ArrayList<>(deduped);
        sorted.sort(java.util.Comparator.comparingDouble(center::distanceToSqr));
        // 闪电链目标：主目标算 1 个，闪电链连接剩余 maxTargets-1 个（maxTargets>0），否则全部
        List<LivingEntity> chainTargets = sorted;
        if (maxTargets > 0) {
            int chainCap = Math.max(0, maxTargets - 1);
            if (sorted.size() > chainCap) {
                chainTargets = sorted.subList(0, chainCap);
            }
        }
        // 体表电流目标：永远 ≤ MAX_SHOCK_TARGETS（性能硬上限），且 ≤ maxTargets-1（若配置）
        int surfaceCap = maxTargets > 0
            ? Math.min(Math.max(0, maxTargets - 1), MAX_SHOCK_TARGETS)
            : MAX_SHOCK_TARGETS;
        List<LivingEntity> surfaceTargets = sorted;
        if (sorted.size() > surfaceCap) {
            surfaceTargets = sorted.subList(0, surfaceCap);
        }

        // 链式电弧（kind=1）：贪心最近邻遍历，从中心出发每次跳到最近的未访问实体
        List<LivingEntity> chain = new ArrayList<>(chainTargets);
        List<Integer> chainIds = new ArrayList<>();
        Vec3 cur = center.position();
        while (!chain.isEmpty()) {
            final Vec3 pos = cur;
            LivingEntity nearest = chain.stream()
                .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(pos))).orElse(null);
            chain.remove(nearest);
            chainIds.add(nearest.getId());
            cur = nearest.position();
        }

        // 链式电弧中心格子 5tick 冷却
        long now = center.level().getGameTime();
        long cell = cellKey(center);
        Long lastChain = CELL_CHAIN_COOLDOWN.get(cell);
        boolean sendChain = lastChain == null || now - lastChain >= 5;
        if (sendChain) {
            CELL_CHAIN_COOLDOWN.put(cell, now);
            CELL_CHAIN_COOLDOWN.values().removeIf(t -> now - t > 10);
        }

        // 向 64 格内所有玩家发送：center 体表电流(kind=0) + deduped 周围体表电流(kind=0) + 链式电弧(kind=1)
        // 1.20.1 SimpleChannel：PacketDistributor.PLAYER.with(() -> sp)
        var pktCenter = new S2CShockFxPayload(0, center.getId(), burstTicks, List.of());
        for (var sp : sl.players()) {
            if (sp.distanceToSqr(center.position()) <= 64.0 * 64.0) {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), pktCenter);
                // 主电弧：攻击者(source)→感电目标(center)，攻击命中瞬间的闪电束（skillB 风格）
                if (source != null && source != center && source.isAlive()) {
                    NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                        new S2CShockFxPayload(1, source.getId(), 0, List.of(center.getId())));
                }
                for (LivingEntity e : surfaceTargets) {
                    NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                        new S2CShockFxPayload(0, e.getId(), burstTicks, List.of()));
                }
                if (sendChain) {
                    NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                        new S2CShockFxPayload(1, center.getId(), 0, chainIds));
                }
            }
        }
    }

    /** 实体所在格子的哈希键（用于位置去重，1格精度）。 */
    private static long cellKey(LivingEntity e) {
        return cellKey(e.getX(), e.getY(), e.getZ());
    }

    private static long cellKey(double x, double y, double z) {
        int ix = Mth.floor(x);
        int iy = Mth.floor(y);
        int iz = Mth.floor(z);
        return ((long)ix << 42) ^ ((long)iy << 21) ^ (long)iz;
    }

    /** 中心格子 → 上次发链式电弧的 gameTick，同格 5tick 内不重复发 kind=1。 */
    private static final java.util.Map<Long, Long> CELL_CHAIN_COOLDOWN = new java.util.HashMap<>();

    /** 体表电流特效的单次目标数量上限（伤害和闪电链不限制，只限最密集的体表电流）。 */
    private static final int MAX_SHOCK_TARGETS = 32;

    /**
     * 以被标记目标为唯一源头，对其周围（半径=range）的其他实体造成一次范围伤害。
     * 周围实体只是受害者，不会被标记、不传播。由 tickControlTimers 每隔 interval tick 调用。
     */
    private static void doShockAoE(LivingEntity center, ShockState state) {
        double radius = Math.max(state.range(), 1.0);
        AABB aabb = center.getBoundingBox().inflate(radius);
        List<LivingEntity> nearby = center.level().getEntitiesOfClass(
            LivingEntity.class, aabb,
            e -> e.isAlive() && !e.isInvulnerable() && e != state.source()
                && center.distanceToSqr(e) <= radius * radius);
        // 感电数量上限：配置了则伤害也限到该数量
        if (state.maxTargets() > 0 && nearby.size() > state.maxTargets()) {
            nearby.sort(java.util.Comparator.comparingDouble(center::distanceToSqr));
            nearby = nearby.subList(0, state.maxTargets());
        }

        if (nearby.isEmpty()) return;

        DISPATCHING.set(true);
        try {
            for (LivingEntity e : nearby) {
                YizModQZKAPI.armorPiercingAndPierceInvulnerabilityDamage(e, state.dmg(), state.source());
            }
            // 吸血：AoE 总伤害按比例治疗 source
            if (state.lifesteal() > 0 && state.source() != null && state.source().isAlive()) {
                float totalDmg = state.dmg() * nearby.size();
                state.source().heal(totalDmg * state.lifesteal());
            }
        } finally {
            DISPATCHING.set(false);
        }

        // 感电视觉：center→周围实体 链式闪电 + 中心/周围体表电流（复用 applyShock 的视觉分发）
        dispatchChainFx(center, state.source(), state.range(), 20, state.maxTargets());
    }

    private static float readShockRange(LivingEntity entity) {
        var inst = entity.getAttribute(YizAttributes.SHOCK_RANGE.get());
        double v = inst != null ? inst.getValue() : 0;
        return v > 0 ? (float) v : 2.5f; // 默认 2.5 格
    }

    private static int readShockInterval(LivingEntity entity) {
        var inst = entity.getAttribute(YizAttributes.SHOCK_INTERVAL.get());
        int v = inst != null ? (int) inst.getValue() : 0;
        return v > 0 ? Math.max(1, v) : 10; // 默认 10 tick
    }

    /** 读感电数量（0 = 未配置，走默认行为）。 */
    private static int readShockCount(LivingEntity entity) {
        var inst = entity != null ? entity.getAttribute(YizAttributes.SHOCK_COUNT.get()) : null;
        double v = inst != null ? inst.getValue() : 0;
        return v > 0 ? (int) v : 0;
    }

    // ══════════════════════════════════════════════════════════════
    //  叠加逻辑
    // ══════════════════════════════════════════════════════════════

    /**
     * 给实体增加控制时间。
     * 同类型：累加。不同类型：取 max（新时间 vs 当前任意类型剩余最大值）。
     */
    private static void addControlTime(LivingEntity target, StatusEffectType type, int ticks) {
        if (ticks <= 0) return;
        UUID uuid = target.getUUID();
        var timers = CONTROL_TIMERS.computeIfAbsent(uuid, k -> new EnumMap<>(StatusEffectType.class));

        Integer existing = timers.get(type);
        int newVal = existing != null ? existing + ticks : ticks; // 同类型加法
        timers.put(type, newVal);

        // 更新 synced CONTROL_TICKS = max(所有)
        if (target instanceof ControlDataBridge bridge) {
            int currentMax = bridge.yizmodqzk$getControlTicks();
            if (newVal > currentMax) {
                bridge.yizmodqzk$setControlTicks(newVal);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  属性读取
    // ══════════════════════════════════════════════════════════════

    private static float readTimeAttr(LivingEntity entity, StatusEffectType type) {
        var attr = TIME_ATTRS.get(type);
        var inst = attr != null ? entity.getAttribute(attr) : null;
        double val = inst != null ? inst.getValue() : 0;
        return val > 0 ? (float) val : DEFAULT_TIME;
    }

    private static float readDamageAttr(LivingEntity entity, StatusEffectType type) {
        var attr = DAMAGE_ATTRS.get(type);
        var inst = attr != null ? entity.getAttribute(attr) : null;
        double val = inst != null ? inst.getValue() : 0;
        return val > 0 ? (float) val : DEFAULT_DAMAGE;
    }

    /**
     * 检查实体当前是否只有击飞控制（无其他硬控）。
     * 击飞不应阻断物理移动，只需冻 AI。
     */
    public static boolean isKnockbackOnly(LivingEntity entity) {
        var timers = CONTROL_TIMERS.get(entity.getUUID());
        if (timers == null || timers.isEmpty()) return false;
        return timers.size() == 1 && timers.containsKey(StatusEffectType.KNOCKBACK);
    }

    /**
     * 是否有硬控（眩晕/冰冻）——需要完全冻结物理移动。
     */
    public static boolean hasHardControl(LivingEntity entity) {
        var timers = CONTROL_TIMERS.get(entity.getUUID());
        if (timers == null || timers.isEmpty()) return false;
        return timers.containsKey(StatusEffectType.STUN)
            || timers.containsKey(StatusEffectType.FREEZE);
    }

    // ══════════════════════════════════════════════════════════════
    //  伤害
    // ══════════════════════════════════════════════════════════════

    private static void dealStatusDamage(LivingEntity target, StatusEffectType type,
                                          float damage, LivingEntity source) {
        DISPATCHING.set(true);
        try {
            switch (type) {
                case FREEZE:
                case SHOCK:
                    YizModQZKAPI.armorPiercingAndPierceInvulnerabilityDamage(target, damage, source);
                    break;
                default:
                    YizModQZKAPI.pierceInvulnerabilityDamage(target, damage, source);
                    break;
            }
        } finally {
            DISPATCHING.set(false);
        }
    }
}
