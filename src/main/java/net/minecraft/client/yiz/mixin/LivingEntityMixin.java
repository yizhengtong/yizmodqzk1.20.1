package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.api.ComboAttackHelper;
import net.minecraft.client.yiz.api.DamageReductionRegistry;
import net.minecraft.client.yiz.api.DamageValueModifierRegistry;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.api.KnockbackImmunityRegistry;
import net.minecraft.client.yiz.api.ProjectileImmunityRegistry;
import net.minecraft.client.yiz.bridge.ControlDataBridge;
import net.minecraft.client.yiz.bridge.HealthDataBridge;
import net.minecraft.client.yiz.bridge.InvulnerableDataBridge;
import net.minecraft.client.yiz.tool.attribute.ItemAttributeHandler;
import net.minecraft.client.yiz.tool.health.ConductionDamageLimiter;
import net.minecraft.client.yiz.tool.health.EntityASMUtil;
import net.minecraft.client.yiz.tool.health.SecureHealthClosure;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.yiz.tool.health.VitalitySeveranceConfig;
import net.minecraft.client.yiz.tool.health.VitalitySeveranceHandler;
import net.minecraft.client.yiz.tool.health.HealthModificationScheduler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * LivingEntity Mixin（1.20.1 移植版）
 * 健康值 Delta 系统 + 禁疗 + 无敌帧 + 减伤 + 传导限伤 + 各种属性驱动的受击/攻击处理。
 *
 * <p>与 1.21.1 差异：defineSynchedData 用无参（1.20.1）+ this.entityData.define；
 * YizAttributes 属性访问用 .get()；AttributeModifier 用 UUID；DataAccessor 用 getId()。</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin implements HealthDataBridge, ControlDataBridge {

    // ==================== DataParameter 定义 ====================

    /** 健康增量 DataParameter。有效血量上限 = maxHealth + delta */
    @Unique
    private static final EntityDataAccessor<Float> yizmodqzk$FE_GET_HEALTH_DATA =
        SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.FLOAT);

    /** SPELL 伤害类型——临时卸下的抗性效果（hurt 后恢复） */
    @Unique
    private static final java.util.Map<LivingEntity, net.minecraft.world.effect.MobEffectInstance>
            yizmodqzk$SPELL_RES_BACKUP = new java.util.concurrent.ConcurrentHashMap<>();

    /** ARMOR/SPELL_DEFENSE 指数减伤参数：锚定 x=20→50%、x=50→75% */
    @Unique
    private static final double yizmodqzk$EXP_REDUCTION_BASE = 40.0;
    @Unique
    private static final double yizmodqzk$EXP_REDUCTION_EXP =
        Math.log(2.0) / Math.log(1.0 + 50.0 / yizmodqzk$EXP_REDUCTION_BASE);

    // ==================== HealthDataBridge 接口实现 ====================

    @Override
    public float yizmodqzk$getHealthDelta() {
        return ((LivingEntity) (Object) this).getEntityData().get(yizmodqzk$FE_GET_HEALTH_DATA);
    }

    @Override
    public void yizmodqzk$setHealthDelta(float delta) {
        ((LivingEntity) (Object) this).getEntityData().set(yizmodqzk$FE_GET_HEALTH_DATA, delta);
    }

    // ==================== ControlDataBridge 接口实现 ====================

    @Unique
    private static final java.util.Map<java.util.UUID, Integer> yizmodqzk$CONTROL_TICKS =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public int yizmodqzk$getControlTicks() {
        return yizmodqzk$CONTROL_TICKS.getOrDefault(((LivingEntity) (Object) this).getUUID(), 0);
    }

    @Override
    public void yizmodqzk$setControlTicks(int ticks) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (ticks <= 0) yizmodqzk$CONTROL_TICKS.remove(self.getUUID());
        else yizmodqzk$CONTROL_TICKS.put(self.getUUID(), ticks);
    }

    // ==================== defineSynchedData ====================

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void yizmodqzk$onDefineSynchedData(CallbackInfo ci) {
        // 1.20.1：defineSynchedData() 无参，用 this.entityData.define
        LivingEntity self = (LivingEntity) (Object) this;
        self.getEntityData().define(yizmodqzk$FE_GET_HEALTH_DATA, 0F);
    }

    // ==================== getHealth / isAlive / isDeadOrDying 改写 ====================

    @Inject(method = "getHealth", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$modifyGetHealth(CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (SecureHealthClosure.isSecure(self)) {
            if (!net.minecraft.client.yiz.tool.health.SecureHealthClosure.isRegistered(self)) {
                net.minecraft.client.yiz.tool.health.SecureHealthClosure.register(self, cir.getReturnValueF());
            }
            cir.setReturnValue(net.minecraft.client.yiz.tool.health.SecureHealthClosure.getHealth(self));
            return;
        }

        if (self instanceof InvulnerableDataBridge iv && iv.yizmodqzk$isInvulnerable()) {
            cir.setReturnValue(Math.max(1.0F, self.getMaxHealth()));
            return;
        }

        float delta = self.getEntityData().get(yizmodqzk$FE_GET_HEALTH_DATA);
        if (delta != 0) {
            float original = cir.getReturnValueF();
            cir.setReturnValue(Math.min(original, self.getMaxHealth() + delta));
        }
    }

    @Inject(method = "isAlive", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$modifyIsAlive(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        float delta = self.getEntityData().get(yizmodqzk$FE_GET_HEALTH_DATA);
        if (delta != 0) {
            cir.setReturnValue(self.getHealth() > 0);
        }
    }

    @Inject(method = "isDeadOrDying", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$modifyIsDeadOrDying(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        float delta = self.getEntityData().get(yizmodqzk$FE_GET_HEALTH_DATA);
        if (delta != 0) {
            cir.setReturnValue(self.getHealth() <= 0);
        }
    }

    // ==================== tick / die 注入 ====================

    @Inject(method = "tick", at = @At("TAIL"))
    private void yizmodqzk$onTick(CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (entity.level().isClientSide()) return;

        net.minecraft.client.yiz.handler.AttackInvulnerabilityTracker.onTick(entity, entity.level().getGameTime());
        net.minecraft.client.yiz.core.StatusEffectDispatcher.tickControlTimers(entity);

        HealthModificationScheduler.tick(entity);
        ConductionDamageLimiter.tick(entity);
        SecureHealthClosure.tick(entity);

        if (entity.tickCount % 10 == 0) {
            VitalitySeveranceHandler.enforceTick(entity);
            VitalitySeveranceHandler.enforceFieldTick(entity);
            net.minecraft.client.yiz.tool.health.HealthWriteGuard.enforce(entity);
        }
    }

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onDie(net.minecraft.world.damagesource.DamageSource source, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        if (net.minecraft.client.yiz.core.PlayerClassSwapper.isProtectedByUuid(entity.getStringUUID())) {
            ci.cancel();
            return;
        }

        entity.getEntityData().set(yizmodqzk$FE_GET_HEALTH_DATA, 0F);
        HealthModificationScheduler.removeAll(entity);
        VitalitySeveranceConfig.remove(entity);
        net.minecraft.client.yiz.tool.health.ShieldTracker.remove(entity);
        ConductionDamageLimiter.removeAll(entity);
        SecureHealthClosure.removeAll(entity);
        net.minecraft.client.yiz.tool.health.HealthWriteGuard.remove(entity);
    }

    // ==================== NBT 持久化 ====================

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void yizmodqzk$addAdditionalSaveData(CompoundTag tag, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        float delta = self.getEntityData().get(yizmodqzk$FE_GET_HEALTH_DATA);
        if (delta != 0) {
            tag.putFloat("yizmodqzk:health_delta", delta);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void yizmodqzk$readAdditionalSaveData(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("yizmodqzk:health_delta", Tag.TAG_FLOAT)) {
            LivingEntity self = (LivingEntity) (Object) this;
            float delta = tag.getFloat("yizmodqzk:health_delta");
            self.getEntityData().set(yizmodqzk$FE_GET_HEALTH_DATA, Math.min(0, delta));
        }
    }

    // ==================== hurt() 伤害修正 ====================

    @ModifyVariable(method = "hurt", at = @At("HEAD"), argsOnly = true)
    private float yizmodqzk$modifyHurtAmount(float amount, DamageSource source) {
        if (amount <= 0) return amount;
        LivingEntity self = (LivingEntity) (Object) this;

        // === 破限附魔：恢复 Player.attack() 原始伤害 ===
        Float expected = net.minecraft.client.yiz.editor.PoxianDamageTracker.get();
        if (expected != null && expected > amount) {
            amount = expected;
        }

        // === 免疫掉落伤害 ===
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
                && self instanceof net.minecraft.world.entity.player.Player pl
                && net.minecraft.client.yiz.handler.FallImmunityTracker.consume(pl)) {
            return 0;
        }

        // === DamageValueModifierRegistry ===
        amount = DamageValueModifierRegistry.apply(self, source, amount);
        if (amount <= 0) return 0;

        // === SPELL：No-harm ===
        boolean isSpell = source.is(net.minecraft.client.yiz.api.YizDamageTypes.SPELL);
        if (isSpell) {
            if (net.minecraft.client.yiz.core.SpellSourceTracker.get() == null) net.minecraft.client.yiz.core.SpellSourceTracker.set("spell");
            var resInst = self.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
            if (resInst != null) {
                float capped = Math.min((resInst.getAmplifier() + 1) * 0.20f, 0.90f);
                amount *= (1.0f - capped);
                self.removeEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
                yizmodqzk$SPELL_RES_BACKUP.put(self, resInst);
            }
        }

        // === 攻击者伤害增幅（SPELL 跳过）===
        if (!isSpell && source.getEntity() instanceof LivingEntity attacker) {
            if (!net.minecraft.client.yiz.core.StatusEffectDispatcher.DISPATCHING.get()) {
                if (attacker instanceof net.minecraft.world.entity.player.Player pl) {
                    boolean vanillaCrit = net.minecraft.client.yiz.api.CritTracker.consume(pl);
                    float[] bonus = net.minecraft.client.yiz.handler.PostSkillAttackTracker.tryConsume(pl);
                    if (bonus != null) { amount += bonus[0]; pl.heal(bonus[1]); }
                }
                if (!self.level().isClientSide()) {
                    var atkEffects = net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.getAttackEffects(attacker);
                    if (!atkEffects.isEmpty())
                        net.minecraft.client.yiz.core.StatusEffectDispatcher.dispatchToTarget(self, atkEffects, attacker);
                    var defEffects = net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.getDefenseEffects(self);
                    if (!defEffects.isEmpty())
                        net.minecraft.client.yiz.core.StatusEffectDispatcher.dispatchToAttacker(attacker, defEffects, self);
                }
            }

            double distSq = attacker.distanceToSqr(self);
            float addSum = 0f;
            for (var holder : new net.minecraftforge.registries.RegistryObject[]{
                net.minecraft.client.yiz.attribute.YizAttributes.GENERIC_DAMAGE,
                (distSq <= 100.0)
                    ? net.minecraft.client.yiz.attribute.YizAttributes.MELEE_DAMAGE
                    : net.minecraft.client.yiz.attribute.YizAttributes.RANGED_DAMAGE
            }) {
                var inst = attacker.getAttribute((net.minecraft.world.entity.ai.attributes.Attribute) holder.get());
                if (inst == null) continue;
                double amp = inst.getValue();
                if (amp <= 0) continue;
                if (YizAttributes.getStackMode(holder) == YizAttributes.StackMode.ADD) {
                    addSum += (float) amp;
                } else {
                    amount *= (1.0F + (float) amp);
                }
            }
            if (addSum > 0) amount *= (1.0F + addSum);
            var atkStr = attacker.getAttribute(YizAttributes.ATTACK_STRENGTH.get());
            if (atkStr != null && atkStr.getValue() > 0) amount *= (1.0F + (float)(atkStr.getValue() / 100.0));
        }

        // === 熔岩/火焰防护 ===
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) {
            var immPct = self.getAttribute(YizAttributes.LAVA_IMMUNE_TIME.get());
            var immFlat = self.getAttribute(YizAttributes.LAVA_IMMUNE_TIME_FLAT.get());
            if ((immPct != null && immPct.getValue() > 0)
                || (immFlat != null && immFlat.getValue() > 0)) {
                return 0;
            }
            var lavaRedPct = self.getAttribute(YizAttributes.LAVA_DAMAGE_REDUCTION.get());
            if (lavaRedPct != null) {
                double red = lavaRedPct.getValue();
                if (red > 0) amount *= (float) (1.0 - Math.min(1.0, red / 100.0));
            }
            var lavaRedFlat = self.getAttribute(YizAttributes.LAVA_DAMAGE_REDUCTION_FLAT.get());
            if (lavaRedFlat != null) {
                double flat = lavaRedFlat.getValue();
                if (flat > 0) amount = Math.max(0, amount - (float) flat);
            }
        }

        // === ARMOR / SPELL_DEFENSE 指数减免 ===
        // 1.20.1 无 IS_PLAYER_ATTACK tag；玩家近战由 IS_FALL 等兜底 + 默认 SPELL_DEFENSE
        boolean isPhysical = source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)
            || source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)
            || source.is(net.minecraft.tags.DamageTypeTags.IS_FALL);
        var expAttr = isPhysical
            ? YizAttributes.ARMOR
            : YizAttributes.SPELL_DEFENSE;
        var expInst = self.getAttribute(expAttr.get());
        if (expInst != null && expInst.getValue() > 0) {
            double reduction = 1.0 - Math.pow(
                    1.0 + expInst.getValue() / yizmodqzk$EXP_REDUCTION_BASE,
                    -yizmodqzk$EXP_REDUCTION_EXP);
            amount *= (float)(1.0 - Math.min(1.0, reduction));
        }

        // 卢登激荡：捕获 overkill 候选
        net.minecraft.client.yiz.handler.LudenOverkillHandler.captureIfLethal(self, source, amount);

        // ── 传导限伤引擎（最后）──
        amount = ConductionDamageLimiter.limitHurt(self, source, amount, self.level().getGameTime());

        return Math.max(0, amount);
    }

    // ==================== setHealth 禁疗拦截 ====================

    @ModifyVariable(method = "setHealth", at = @At("HEAD"), argsOnly = true)
    private float yizmodqzk$modifyHealthForVitalitySeverance(float newHealth) {
        if (EntityASMUtil.consumeVitalitySeveranceFlag()) {
            return newHealth;
        }

        LivingEntity self = (LivingEntity) (Object) this;

        if (SecureHealthClosure.isSecure(self)) {
            if (!net.minecraft.client.yiz.tool.health.SecureHealthClosure.isRegistered(self)) {
                net.minecraft.client.yiz.tool.health.SecureHealthClosure.register(self, self.getHealth());
            }
            float next = ConductionDamageLimiter.limitSetHealth(self, newHealth, self.level().getGameTime());
            next = Math.max(0.0F, next);
            net.minecraft.client.yiz.tool.health.SecureHealthClosure.setHealth(self, next);
            return next;
        }

        if (net.minecraft.client.yiz.core.PlayerClassSwapper.isProtectedByUuid(self.getStringUUID())) {
            return EntityASMUtil.clampProtectedHealth(newHealth);
        }

        if (net.minecraft.client.yiz.core.SpellSourceTracker.isActive()) {
            return newHealth;
        }

        float current = self.getHealth();
        net.minecraft.client.yiz.tool.health.EntityASMUtil.clearArmorPenetration();

        if (newHealth < current) {
            var reductionInst = self.getAttribute(YizAttributes.DAMAGE_REDUCTION.get());
            if (reductionInst != null) {
                double reduction = reductionInst.getValue();
                if (reduction > 0) {
                    float damage = current - newHealth;
                    damage *= (float) (1.0 - Math.min(1.0, reduction / 100.0));
                    newHealth = current - damage;
                }
            }
            var blockInst = self.getAttribute(YizAttributes.DAMAGE_BLOCK.get());
            if (blockInst != null) {
                double block = blockInst.getValue();
                if (block > 0) {
                    float damage = current - newHealth;
                    damage = Math.max(0, damage - (float) block);
                    newHealth = current - damage;
                }
            }
            float shield = net.minecraft.client.yiz.tool.health.ShieldTracker.get(self);
            if (shield > 0) {
                float damage = current - newHealth;
                float absorbed = Math.min(damage, shield);
                net.minecraft.client.yiz.tool.health.ShieldTracker.set(self, shield - absorbed);
                newHealth = current - (damage - absorbed);
            }
            newHealth = DamageReductionRegistry.applyBeforeSetHealth(self, newHealth);
            newHealth = ConductionDamageLimiter.limitSetHealth(self, newHealth, self.level().getGameTime());
            return newHealth;
        }

        if (newHealth <= current) return newHealth;
        if (current <= 0.5f) return newHealth;

        try {
            float healing = newHealth - current;
            var config = VitalitySeveranceConfig.get(self);
            if (config != null) {
                healing = config.apply(healing);
            }
            float tempBan = net.minecraft.client.yiz.tool.health.VitalitySeveranceHandler.getBanFactor(self);
            if (tempBan > 0) {
                healing *= (1.0f - Math.min(1.0f, tempBan));
            }
            return current + Math.max(0, healing);
        } catch (Exception e) {
            return newHealth;
        }
    }

    @Inject(method = "setHealth", at = @At("RETURN"))
    private void yizmodqzk$onSetHealth(CallbackInfo ci) {
        VitalitySeveranceHandler.updateBaseline((LivingEntity) (Object) this);
    }

    // ==================== hurt HEAD / RETURN ====================

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onHurtPre(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (!self.level().isClientSide() && source.getEntity() instanceof LivingEntity poshiAttacker
                && net.minecraft.client.yiz.editor.PoshiBypassBridge.shouldBypass(poshiAttacker)) {
            self.invulnerableTime = 0;
        }

        if (net.minecraft.client.yiz.handler.AttackInvulnerabilityTracker.onHurtHead(self)
                == net.minecraft.client.yiz.handler.AttackInvulnerabilityTracker.HurtHeadResult.CANCEL) {
            cir.setReturnValue(false);
            return;
        }

        if (source.getEntity() instanceof LivingEntity attacker)
            net.minecraft.client.yiz.handler.InvulnBreakHandler.apply(attacker, self);

        if (source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)) {
            var inst = self.getAttribute(YizAttributes.PROJECTILE_IMMUNITY.get());
            if (inst != null) {
                double v = inst.getValue();
                if (v >= 100.0 || (v > 0 && Math.random() < v / 100.0)) {
                    cir.setReturnValue(false);
                }
            }
        }
    }

    @Inject(method = "hurt", at = @At("RETURN"))
    private void yizmodqzk$onHurtReturn(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) return;
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;

        net.minecraft.client.yiz.handler.AttackInvulnerabilityTracker.onHurtSuccess(self, self.level().getGameTime());

        Entity srcEntity = source.getEntity();
        if (!(srcEntity instanceof LivingEntity attacker)) return;

        boolean isSpell = net.minecraft.client.yiz.core.SpellSourceTracker.isActive();
        var backedUp = yizmodqzk$SPELL_RES_BACKUP.remove(self);
        if (isSpell) {
            if (backedUp != null) self.addEffect(backedUp);
            net.minecraft.client.yiz.core.SpellSourceTracker.remove();
        }
        if (isSpell) return;

        if (srcEntity instanceof net.minecraft.server.level.ServerPlayer spAttacker && !(self instanceof Player)
            && !net.minecraft.client.yiz.core.StatusEffectDispatcher.DISPATCHING.get()) {
            net.minecraft.client.yiz.editor.EnhanceTagRegistry.onPlayerAttack(spAttacker);
            net.minecraft.client.yiz.tizMod.dispatchPassiveAttack(spAttacker, self);
        }

        if (self instanceof Player player && attacker != player) {
            double rate = player.getAttributeValue(YizAttributes.COUNTER_RATE.get());
            if (rate > 0 && Math.random() < rate / 100.0) {
                double value = player.getAttributeValue(YizAttributes.COUNTER_VALUE.get());
                if (value <= 0) value = 50.0;
                double playerAtk = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
                float counterDmg = (float) (playerAtk * value / 100.0);
                attacker.hurt(player.damageSources().mobAttack(player), counterDmg);
            }
        }

        if (srcEntity instanceof net.minecraft.server.level.ServerPlayer cPlayer && !(self instanceof Player)
            && !ComboAttackHelper.isComboAttacking()) {
            double cRate = cPlayer.getAttributeValue(YizAttributes.COMBO_RATE.get());
            if (cRate > 0 && Math.random() < cRate / 100.0) {
                ComboAttackHelper.executeCombo(cPlayer, self);
            }
        }

        if (amount > 0) {
            var lsInst = attacker.getAttribute(YizAttributes.LIFE_STEAL.get());
            double ls = lsInst != null ? lsInst.getValue() : 0;
            if (ls > 0) attacker.heal((float)(amount * ls / 100.0));
            // 吸血扩展：额外回复「最初梦幻数值」的 10%（FIRST_DREAM 属性值）
            var dreamInst = attacker.getAttribute(YizAttributes.FIRST_DREAM.get());
            if (dreamInst != null && dreamInst.getValue() > 0) {
                attacker.heal((float)(dreamInst.getValue() * 0.10));
            }
        }

        net.minecraft.client.yiz.tool.health.VitalitySeverance.apply(attacker, self);
    }

    // ==================== 击退免疫 / 移除保护 / 水下呼吸 / 步高 / 护甲穿透 ====================

    @Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onKnockback(double d0, double d1, double d2, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        var inst = self.getAttribute(YizAttributes.KNOCKBACK_IMMUNITY.get());
        if (inst != null && inst.getValue() > 0) {
            ci.cancel();
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onRemove(net.minecraft.world.entity.Entity.RemovalReason reason, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (net.minecraft.client.yiz.core.PlayerClassSwapper.isProtectedByUuid(self.getStringUUID())) {
            ci.cancel();
        }
    }

    @Inject(method = "decreaseAirSupply", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$modifyAirDecrease(int currentAir, CallbackInfoReturnable<Integer> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        var pctInst = self.getAttribute(YizAttributes.WATER_BREATH_TIME.get());
        var flatInst = self.getAttribute(YizAttributes.WATER_BREATH_TIME_FLAT.get());
        double pct = pctInst != null ? pctInst.getValue() : 0;
        double flat = flatInst != null ? flatInst.getValue() : 0;
        if (pct <= 0 && flat <= 0) return;
        if (pct >= 100) { cir.setReturnValue(currentAir); return; }
        int maxAir = self.getMaxAirSupply();
        int effectiveMax = maxAir + (int) flat;
        if (pct > 0) effectiveMax = (int)(effectiveMax * (1.0 + pct / 100.0));
        int decreased = cir.getReturnValue();
        double ratio = (double) maxAir / (double) Math.max(1, effectiveMax);
        cir.setReturnValue(currentAir - Math.max(0, (int)((currentAir - decreased) * ratio)));
    }

    @Inject(method = "maxUpStep", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$modifyStepHeight(CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        float extra = 0f;
        var inst = self.getAttribute(YizAttributes.JUMP_SPEED.get());
        if (inst != null) extra += (float) inst.getValue();
        if (extra > 0) cir.setReturnValue(cir.getReturnValue() + extra);
    }

    @Inject(method = "getArmorValue", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$applyArmorPenetration(CallbackInfoReturnable<Integer> cir) {
        float penPct = EntityASMUtil.peekArmorPenPct();
        float penFlat = EntityASMUtil.peekArmorPenFlat();
        if (penPct > 0 || penFlat > 0) {
            int armor = cir.getReturnValue();
            if (penPct > 0) {
                armor = armor - (int)(armor * penPct / 100f);
            }
            if (penFlat > 0) {
                armor = armor - (int) penFlat;
            }
            cir.setReturnValue(Math.max(0, armor));
        }
    }
}
