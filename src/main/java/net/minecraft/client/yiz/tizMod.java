package net.minecraft.client.yiz;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.tool.attribute.ItemAttributeHandler;

/**
 * YizMod QZK — 1.20.1 Forge 前置库主类。
 *
 * <p>包结构对齐 1.21.1（{@code net.minecraft.client.yiz} 家族包根）——
 * {@link net.minecraft.client.yiz.tool.attribute.EntityAttributeGate} 的调用栈鉴权
 * 信任 {@code net.minecraft.client.yiz} 前缀，所有前置库设施类必须在此家族包根下。</p>
 */
@Mod(tizMod.MODID)
public class tizMod {

    public static final String MODID = "yizmodqzk";
    public static final Logger LOGGER = LogUtils.getLogger();

    public tizMod() {
        var modEventBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();

        // 注册自定义属性（辖界者等自研实体挂载）
        YizAttributes.ATTRIBUTES.register(modEventBus);
        // 属性编辑台工作方块（Block/Item/CreativeTab/BlockEntity/Menu）
        net.minecraft.client.yiz.editor.AttributeEditorRegistries.register(modEventBus);

        // 注册玩家实体属性挂载（自定义属性需要挂到 EntityType 上才能 getAttribute）
        modEventBus.addListener((net.minecraftforge.event.entity.EntityAttributeModificationEvent e) -> {
            net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.LivingEntity> player =
                net.minecraft.world.entity.EntityType.PLAYER;
            e.add(player, YizAttributes.ATTACK_STRENGTH.get());
            e.add(player, YizAttributes.SPELL_POWER.get());
            e.add(player, YizAttributes.GENERIC_DAMAGE.get());
            e.add(player, YizAttributes.MELEE_DAMAGE.get());
            e.add(player, YizAttributes.RANGED_DAMAGE.get());
            e.add(player, YizAttributes.DAMAGE_REDUCTION.get());
            e.add(player, YizAttributes.DAMAGE_BLOCK.get());
            e.add(player, YizAttributes.INVINCIBILITY_MULT.get());
            e.add(player, YizAttributes.DODGE_CHANCE.get());
            e.add(player, YizAttributes.LIFE_STEAL.get());
            e.add(player, YizAttributes.ARMOR.get());
            e.add(player, YizAttributes.SPELL_DEFENSE.get());
            e.add(player, YizAttributes.VITALITY_SEVERANCE_RATE.get());
            e.add(player, YizAttributes.VITALITY_SEVERANCE_TIME.get());
            e.add(player, YizAttributes.FIRST_DREAM.get());
            e.add(player, YizAttributes.DREAM_PERCENT.get());
            e.add(player, YizAttributes.CONDUCTION_CAP.get());
            e.add(player, YizAttributes.SECURE_PULSE.get());
            // LivingEntityMixin 扩展属性
            e.add(player, YizAttributes.CRIT_RATE.get());
            e.add(player, YizAttributes.CRIT_DAMAGE.get());
            e.add(player, YizAttributes.PRECISION.get());
            e.add(player, YizAttributes.COMBO_RATE.get());
            e.add(player, YizAttributes.COUNTER_RATE.get());
            e.add(player, YizAttributes.COUNTER_VALUE.get());
            e.add(player, YizAttributes.JUMP_SPEED.get());
            e.add(player, YizAttributes.KNOCKBACK_IMMUNITY.get());
            e.add(player, YizAttributes.PROJECTILE_IMMUNITY.get());
            e.add(player, YizAttributes.UNDYING.get());
            e.add(player, YizAttributes.MANA_COST_REDUCTION.get());
            e.add(player, YizAttributes.MAGIC_DAMAGE.get());
            e.add(player, YizAttributes.LAVA_IMMUNE_TIME.get());
            e.add(player, YizAttributes.LAVA_IMMUNE_TIME_FLAT.get());
            e.add(player, YizAttributes.LAVA_DAMAGE_REDUCTION.get());
            e.add(player, YizAttributes.LAVA_DAMAGE_REDUCTION_FLAT.get());
            e.add(player, YizAttributes.WATER_BREATH_TIME.get());
            e.add(player, YizAttributes.WATER_BREATH_TIME_FLAT.get());
            e.add(player, YizAttributes.ARMOR_PENETRATION.get());
            e.add(player, YizAttributes.ARMOR_PENETRATION_FLAT.get());
            // 技能系统属性
            e.add(player, YizAttributes.COOLDOWN_VALUE.get());
            e.add(player, YizAttributes.MAX_CHARGES.get());
            e.add(player, YizAttributes.SKILL_RANGE.get());
            e.add(player, YizAttributes.SKILL_INTERVAL.get());
            e.add(player, YizAttributes.COOLDOWN_REDUCTION.get());
            e.add(player, YizAttributes.COMBO_VALUE.get());
            e.add(player, YizAttributes.COMBO_COUNT.get());
            e.add(player, YizAttributes.DAMAGE_BASE.get());
            e.add(player, YizAttributes.DAMAGE_SPELL_COEFF.get());
            e.add(player, YizAttributes.HEAL_BASE.get());
            e.add(player, YizAttributes.HEAL_HP_COEFF.get());

            // 1.21.1 移植属性挂载（2026-08-26）：组A 死属性 7 + 组B 玩家向独立 24
            e.add(player, YizAttributes.SHIELD_VALUE.get());
            e.add(player, YizAttributes.DAMAGE_TYPE.get());
            e.add(player, YizAttributes.HEAL_ATK_COEFF.get());
            e.add(player, YizAttributes.HEAL_SPELL_COEFF.get());
            e.add(player, YizAttributes.FLIGHT_TIME.get());
            e.add(player, YizAttributes.MAX_SENTRIES.get());
            e.add(player, YizAttributes.ON_HURT.get());
            e.add(player, YizAttributes.MOVE_SPEED.get());
            e.add(player, YizAttributes.MAX_RUN_SPEED.get());
            e.add(player, YizAttributes.AIR_SPEED.get());
            e.add(player, YizAttributes.JUMP_STRENGTH.get());
            e.add(player, YizAttributes.FALL_SAFE.get());
            e.add(player, YizAttributes.FALL_REDUCE.get());
            e.add(player, YizAttributes.MINING_LEVEL.get());
            e.add(player, YizAttributes.MINING_PICKAXE.get());
            e.add(player, YizAttributes.MINING_AXE.get());
            e.add(player, YizAttributes.MINING_SHOVEL.get());
            e.add(player, YizAttributes.MINING_ALL.get());
            e.add(player, YizAttributes.MINING_PENALTY_IMMUNITY.get());
            e.add(player, YizAttributes.MINING_EFFICIENCY.get());
            e.add(player, YizAttributes.MAX_MANA.get());
            e.add(player, YizAttributes.MANA_REGEN.get());
            e.add(player, YizAttributes.MANA_REGEN_PCT.get());
            e.add(player, YizAttributes.LIFE_REGEN_RATE.get());
            e.add(player, YizAttributes.LIFE_REGEN_PCT.get());
            e.add(player, YizAttributes.POSHI.get());
            e.add(player, YizAttributes.POXIAN.get());
            e.add(player, YizAttributes.PROJECTILE_REFLECTION.get());
            e.add(player, YizAttributes.NO_COLLISION.get());
            e.add(player, YizAttributes.ATTACK_RANGE.get());
            e.add(player, YizAttributes.AUTO_ATTACK.get());

            // 组C 状态五系 22 属性挂载（2026-08-26 阶段4）
            e.add(player, YizAttributes.STUN_ATTACK.get());
            e.add(player, YizAttributes.SLOW_ATTACK.get());
            e.add(player, YizAttributes.FREEZE_ATTACK.get());
            e.add(player, YizAttributes.SHOCK_ATTACK.get());
            e.add(player, YizAttributes.KNOCKBACK_ATTACK.get());
            e.add(player, YizAttributes.STUN_DEFENSE.get());
            e.add(player, YizAttributes.SLOW_DEFENSE.get());
            e.add(player, YizAttributes.FREEZE_DEFENSE.get());
            e.add(player, YizAttributes.SHOCK_DEFENSE.get());
            e.add(player, YizAttributes.KNOCKBACK_DEFENSE.get());
            e.add(player, YizAttributes.STUN_TIME.get());
            e.add(player, YizAttributes.SLOW_TIME.get());
            e.add(player, YizAttributes.FREEZE_TIME.get());
            e.add(player, YizAttributes.SHOCK_TIME.get());
            e.add(player, YizAttributes.KNOCKBACK_TIME.get());
            e.add(player, YizAttributes.SHOCK_RANGE.get());
            e.add(player, YizAttributes.SHOCK_INTERVAL.get());
            e.add(player, YizAttributes.STUN_DAMAGE.get());
            e.add(player, YizAttributes.SLOW_DAMAGE.get());
            e.add(player, YizAttributes.FREEZE_DAMAGE.get());
            e.add(player, YizAttributes.SHOCK_DAMAGE.get());
            e.add(player, YizAttributes.KNOCKBACK_DAMAGE.get());
            e.add(player, YizAttributes.SHOCK_COUNT.get());

            // 组D 依赖下游系统 11 属性挂载（2026-08-26 阶段7）
            e.add(player, YizAttributes.SPLASH_RADIUS.get());
            e.add(player, YizAttributes.SPLASH_DAMAGE.get());
            e.add(player, YizAttributes.SPLASH_FALLOFF.get());
            e.add(player, YizAttributes.HUIXIN.get());
            e.add(player, YizAttributes.KEGONG.get());
            e.add(player, YizAttributes.JUMP_COUNT.get());
            e.add(player, YizAttributes.JUMP_HEIGHT.get());
            e.add(player, YizAttributes.MAX_MINIONS.get());
            e.add(player, YizAttributes.SUMMON_DAMAGE.get());
            e.add(player, YizAttributes.MANA_COST.get());
            e.add(player, YizAttributes.MANA_COST_PER_SEC.get());
        });

        // 状态效果属性绑定：攻方(攻击者携带命中施加)/防方(受击者携带受击施加)
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerAttack(
            YizAttributes.STUN_ATTACK, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.STUN);
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerAttack(
            YizAttributes.SLOW_ATTACK, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.SLOW);
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerAttack(
            YizAttributes.FREEZE_ATTACK, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.FREEZE);
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerAttack(
            YizAttributes.SHOCK_ATTACK, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.SHOCK);
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerAttack(
            YizAttributes.KNOCKBACK_ATTACK, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.KNOCKBACK);
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerDefense(
            YizAttributes.STUN_DEFENSE, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.STUN);
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerDefense(
            YizAttributes.SLOW_DEFENSE, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.SLOW);
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerDefense(
            YizAttributes.FREEZE_DEFENSE, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.FREEZE);
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerDefense(
            YizAttributes.SHOCK_DEFENSE, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.SHOCK);
        net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.registerDefense(
            YizAttributes.KNOCKBACK_DEFENSE, net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.KNOCKBACK);

        // 加载实体真实血量字段定位缓存（config/yizmodqzk/entity_health_slots.json）
        net.minecraft.client.yiz.tool.health.EntityHealthLocator.load();
        // 加载声明式改血覆盖（config/yizmodqzk/health_overrides.json：槽 + 门控）
        net.minecraft.client.yiz.tool.health.HealthOverridesConfig.load();

        // 简易指令注册器 + /yiz remove / /yiz agent / /yiz setHealth / /yiz key 指令
        net.minecraft.client.yiz.tool.SimpleCommandRegistry.init();
        net.minecraft.client.yiz.tool.YizRemoveCommand.register();
        net.minecraft.client.yiz.tool.YizAgentCommand.register();
        net.minecraft.client.yiz.tool.YizSetHealthCommand.register();
        net.minecraft.client.yiz.tool.YizHealthLocateCommand.register();
        net.minecraft.client.yiz.tool.YizKeyCommand.register();
        // 闪电特效测试指令（/yiz fx）
        net.minecraft.client.yiz.tool.YizFxCommand.register();
        // 物品描边指令（/yiz mb <0-5> 给主手物品加描边）
        net.minecraft.client.yiz.tool.YizOutlineCommand.register();

        modEventBus.addListener(this::commonSetup);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        // 挖掘属性事件（BreakSpeed/HarvestCheck，1.21.1 由 PlayerMiningMixin 注入，1.20.1 改用事件）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
            net.minecraft.client.yiz.handler.MiningAttributeHandler.class);
        // 锁定系统（HUIXIN/KEGONG 服务端充能状态机）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
            net.minecraft.client.yiz.handler.LockOnHandler.class);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("YizMod QZK 1.20.1 前置库初始化完成");
        // 感电视觉 S2C 网络通道（1.20.1 SimpleChannel）
        net.minecraft.client.yiz.network.NetworkHandler.register();
        // 动态加载 JavaAgent（同进程 self-attach，用于涨跌多空 getHealth 字节码改写配合扫描）
        try {
            net.minecraft.client.yiz.core.asm.AgentLoader.init();
        } catch (Throwable t) {
            LOGGER.warn("Agent 加载跳过（不影响血量扫描体系）: {}", t.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.debug("YizMod QZK 服务端启动");
    }

    /** 被动攻击分发：遍历被动/装备槽 IPassiveItem，调用 onAttack。由 LivingEntityMixin.onHurtReturn 调用。 */
    public static void dispatchPassiveAttack(net.minecraft.server.level.ServerPlayer player,
                                             net.minecraft.world.entity.LivingEntity target) {
        if (player.level().isClientSide()) return;
        try {
            var data = net.minecraft.client.yiz.editor.SkillConfigStorage.get(player.getUUID());
            if (data == null) return;
            for (int i = 0; i < data.passiveLoad().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = data.passiveLoad().getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.client.yiz.api.IPassiveItem passive) {
                    passive.onAttack(player, stack, target);
                }
            }
            for (int i = 0; i < data.equipment().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = data.equipment().getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.client.yiz.api.IPassiveItem passive) {
                    passive.onAttack(player, stack, target);
                }
            }
        } catch (Throwable ignored) {}
    }

    //  防御属性镜像（供下游自研实体调用）

    /**
     * 防御力镜像：读 {@link YizAttributes#ARMOR} → 1:1 写到原版 {@link Attributes#ARMOR} + {@link Attributes#ARMOR_TOUGHNESS}。
     */
    public static void mirrorArmor(LivingEntity entity) {
        var inst = entity.getAttribute(YizAttributes.ARMOR.get());
        if (inst == null) return;
        double armor = inst.getValue();
        ItemAttributeHandler.setEntityAttribute(
            entity, Attributes.ARMOR, "yiz_armor_mirror", armor, AttributeModifier.Operation.ADDITION);
        ItemAttributeHandler.setEntityAttribute(
            entity, Attributes.ARMOR_TOUGHNESS, "yiz_toughness_mirror", armor, AttributeModifier.Operation.ADDITION);
    }

    /**
     * 法术防御镜像：读 {@link YizAttributes#SPELL_DEFENSE} → 写原版击退韧性；>20 时同时开击退免疫/无碰撞。
     */
    public static void mirrorSpellDefense(LivingEntity entity) {
        var inst = entity.getAttribute(YizAttributes.SPELL_DEFENSE.get());
        if (inst == null) return;
        double val = inst.getValue();
        if (val <= 20.0) {
            ItemAttributeHandler.setEntityAttribute(
                entity, Attributes.KNOCKBACK_RESISTANCE, "yiz_spell_defense_mirror", val, AttributeModifier.Operation.ADDITION);
        } else {
            ItemAttributeHandler.setEntityAttribute(
                entity, Attributes.KNOCKBACK_RESISTANCE, "yiz_spell_defense_mirror", 0, AttributeModifier.Operation.ADDITION);
        }
    }

    //  1.21.1 移植属性 tick 驱动（2026-08-26）：法力/生命回复/攻击距离/弹射物反射

    /** 每 tick 驱动：弹射物反射 + 法力回复 + 生命回复（ATTACK_RANGE 由 PlayerRangeMixin 注入交互距离 getter）。 */
    @SubscribeEvent
    public void onPlayerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.world.entity.player.Player player = event.player;
        if (player == null || player.level().isClientSide()) return;

        net.minecraft.client.yiz.api.ProjectileReflectionSystem.tick(player);
        // 多段跳服务端充能 + 空中 cap
        net.minecraft.client.yiz.handler.MultiJumpRechargeHandler.onPlayerTick(event);

        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            // 方案 A：穿戴物品 NBT 属性聚合到实体（每 tick）
            net.minecraft.client.yiz.tool.attribute.NbtAttributeAggregator.aggregate(sp);
            net.minecraft.client.yiz.tool.health.ManaTracker.tickRegen(sp);
            net.minecraft.client.yiz.tool.health.AttributeEffectTicker.tick(sp);
            net.minecraft.client.yiz.tool.health.ManaCostDrain.tick(sp);
        }
    }

    /** 多段跳落地充能（LivingFallEvent）。 */
    @SubscribeEvent
    public void onLivingFall(net.minecraftforge.event.entity.living.LivingFallEvent event) {
        net.minecraft.client.yiz.handler.MultiJumpRechargeHandler.onLivingFall(event);
    }
}
