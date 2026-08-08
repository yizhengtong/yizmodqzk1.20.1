package net.minecraft.client.yiz.editor;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.yiz.api.IEnhanceable;
import net.minecraft.client.yiz.api.ISkillItem;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.UUID;

/**
 * 全局强化标签注册表。
 *
 * <p>每个标签定义：key、显示名、描述、触发效果。
 * 被动物品通过 {@code getProvidedTags()} 提供标签池，
 * 主动技能通过强化槽激活标签 → 释放时执行。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * EnhanceTagRegistry.register("passive_a_proc", "被动A触发",
 *     "双重施法+刷新全部冷却", ctx -> {
 *         // 双重施法
 *         ItemStack item = ctx.skillItem();
 *         if (item.getItem() instanceof ISkillItem si) si.onCast(ctx.player(), item);
 *         // 刷新冷却
 *         SkillChargeManager.refreshAllCooldowns(ctx.player());
 *     });
 * }</pre>
 *
 * <p>1.20.1 移植说明：
 * <ul>
 *   <li>框架层（TagContext/TagDef/注册/查询/执行）逐行照搬 1.21.1。</li>
 *   <li>{@link AttributeModifier} id 用 {@link UUID}（由原 ResourceLocation key 派生，与
 *       EntityAttributeGate 的 {@code nameUUIDFromBytes} 约定一致）；1.21.1 的
 *       {@code ADD_VALUE} 对应 1.20.1 的 {@link AttributeModifier.Operation#ADDITION}。</li>
 *   <li>属性引用 {@link YizAttributes} 字段为 {@code RegistryObject<Attribute>}，访问加 {@code .get()}。</li>
 *   <li>内置标签引用的技能子系统尚未移植，本次"保持结构 + TODO 占位"（父任务要求），
 *       待下列依赖移植后本文件自动编译可用：</li>
 *   <li>TODO(1.20.1-port) 缺失依赖清单：
 *       api/IEnhanceable、api/ISkillItem、api/SkillTriggerType、api/SkillCastMode、api/EffectSources、
 *       editor/EnhanceEntry、editor/SkillConfigStorage、handler/LastCastSlotTracker、
 *       handler/SkillChargeManager、tool/skill/SkillRanges、tool/skill/SkillIntervals、
 *       network/S2CBenleixiWindowPayload、attribute/YizAttributes.COOLDOWN_REDUCTION。</li>
 * </ul></p>
 */
public final class EnhanceTagRegistry {

    private EnhanceTagRegistry() {}

    /**
     * 标签命中上下文：技能释放时传递给标签效果。
     *
     * @param player      施法玩家（服务端权威）
     * @param skillItem   技能物品
     * @param slot        槽位编号（0=大槽, 1-3=技能槽）
     * @param triggerType 触发时机（ACTIVATE=开启那一刻, TRIGGER=造成效果那一刻）
     * @param target      TRIGGER 时为本次命中的实体；ACTIVATE 时为 null
     */
    public record TagContext(ServerPlayer player, ItemStack skillItem, int slot,
                             net.minecraft.client.yiz.api.SkillTriggerType triggerType,
                             net.minecraft.world.entity.LivingEntity target) {}

    private record TagDef(String displayName, String description,
                          java.util.Set<net.minecraft.client.yiz.api.SkillTriggerType> timing,
                          Consumer<TagContext> effect) {}

    private static final Map<String, TagDef> REGISTRY = new ConcurrentHashMap<>();

    /** 重入守卫：防止标签效果（如 double_cast 再调 onCast→onActivate）导致递归 StackOverflow。 */
    private static final ThreadLocal<Boolean> DISPATCHING = ThreadLocal.withInitial(() -> false);

    /**
     * 注册一个全局标签（旧签名，向后兼容）。
     * <p>默认只在 {@link net.minecraft.client.yiz.api.SkillTriggerType#ACTIVATE} 时机生效。
     * 现有 8 个内置标签零改动即可继续工作。</p>
     */
    public static void register(String key, String displayName, String description, Consumer<TagContext> effect) {
        register(key, displayName, description, java.util.EnumSet.of(net.minecraft.client.yiz.api.SkillTriggerType.ACTIVATE), effect);
    }

    /**
     * 注册一个全局标签，显式声明生效时机。
     *
     * @param timing 该标签在哪些时机生效（ACTIVATE/TRIGGER 的任意子集）
     */
    public static void register(String key, String displayName, String description,
                                java.util.Set<net.minecraft.client.yiz.api.SkillTriggerType> timing,
                                Consumer<TagContext> effect) {
        REGISTRY.put(key, new TagDef(displayName, description, timing, effect));
    }

    /** 获取标签显示名。 */
    public static String displayName(String key) {
        TagDef def = REGISTRY.get(key);
        return def != null ? def.displayName() : key;
    }

    /** 获取标签描述。 */
    public static String description(String key) {
        TagDef def = REGISTRY.get(key);
        return def != null ? def.description() : "";
    }

    /** 所有已注册的标签 key 集合。 */
    public static Set<String> allKeys() { return REGISTRY.keySet(); }

    /** 执行指定标签的效果。 */
    public static void execute(String key, TagContext ctx) {
        TagDef def = REGISTRY.get(key);
        if (def != null) def.effect().accept(ctx);
    }

    /**
     * 执行技能物品上所有已激活、且声明了指定时机的标签。
     *
     * @param type   触发时机（ACTIVATE=开启入口调用, TRIGGER=触发入口调用）
     * @param target TRIGGER 时为本次命中的实体；ACTIVATE 时传 null
     */
    public static void executeActiveTags(ServerPlayer player, ItemStack skillItem, int slot,
                                         net.minecraft.client.yiz.api.SkillTriggerType type,
                                         net.minecraft.world.entity.LivingEntity target) {
        // 重入守卫：标签效果可能回调 onCast→onActivate 再次进入此处（如 double_cast），直接跳过防递归。
        if (DISPATCHING.get()) return;
        // TODO(1.20.1-port): 依赖 api/IEnhanceable、editor/SkillConfigStorage、editor/EnhanceEntry
        if (!(skillItem.getItem() instanceof IEnhanceable e)) return;
        var entries = e.getEnhanceEntries(skillItem, player);
        int[] levels = SkillConfigStorage.getEnhanceLevels(skillItem);
        TagContext ctx = new TagContext(player, skillItem, slot, type, target);
        DISPATCHING.set(true);
        try {
            for (int i = 0; i < Math.min(entries.size(), levels.length); i++) {
                if (entries.get(i) instanceof EnhanceEntry.Tag tag && levels[i] > 0) {
                    TagDef def = REGISTRY.get(tag.key());
                    // 按时机过滤：标签未声明该时机则跳过
                    if (def != null && def.timing().contains(type)) {
                        execute(tag.key(), ctx);
                    }
                }
            }
        } finally {
            DISPATCHING.remove();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  内置标签注册
    // ═══════════════════════════════════════════════════════════

    static {
        registerExistingTags();
    }

    private static void registerExistingTags() {
        register("double_cast", "双重施法",
            "技能释放时额外再执行一次",
            // TODO(1.20.1-port): 依赖 api/ISkillItem、handler/LastCastSlotTracker
            ctx -> {
                ItemStack item = ctx.skillItem();
                if (item.getItem() instanceof ISkillItem si) {
                    net.minecraft.client.yiz.handler.LastCastSlotTracker.set(ctx.slot());
                    si.onCast(ctx.player(), item);
                    net.minecraft.client.yiz.handler.LastCastSlotTracker.clear();
                }
            });

        register("refresh_cooldowns", "刷新冷却",
            "释放后重置全部技能充能至最大值",
            // TODO(1.20.1-port): 依赖 handler/SkillChargeManager
            ctx -> net.minecraft.client.yiz.handler.SkillChargeManager.grantTempBuff(ctx.player()));

        register("double_with_refresh", "被动A触发",
            "双重施法 + 刷新全部冷却",
            // TODO(1.20.1-port): 依赖 api/ISkillItem、handler/LastCastSlotTracker、handler/SkillChargeManager
            ctx -> {
                ItemStack item = ctx.skillItem();
                if (item.getItem() instanceof ISkillItem si) {
                    net.minecraft.client.yiz.handler.LastCastSlotTracker.set(ctx.slot());
                    si.onCast(ctx.player(), item);
                    net.minecraft.client.yiz.handler.LastCastSlotTracker.clear();
                }
                net.minecraft.client.yiz.handler.SkillChargeManager.grantTempBuff(ctx.player());
            });

        // ── 奔雷袭系列八大强化 ──

        register("wuyingji", "无影击",
            "获得100%攻击速度，4次攻击后移除",
            // TODO(1.20.1-port): 依赖 attribute/YizAttributes.COOLDOWN_REDUCTION（1.21.1 注册 id "cooldown_reduction"）
            ctx -> addAttackModifier(ctx.player(), "yiz:wuyingji",
                net.minecraft.client.yiz.attribute.YizAttributes.COOLDOWN_REDUCTION, 100.0, 4));

        register("gandian", "感电",
            "使用技能后减少20%的技能冷却时间",
            // TODO(1.20.1-port): 依赖 handler/SkillChargeManager
            ctx -> net.minecraft.client.yiz.handler.SkillChargeManager.reduceCooldown(ctx.player(), ctx.slot(), 0.20f));

        register("leizhenqianli", "雷震千里",
            "按键型技能释放时创建1秒击退窗口；开关型技能每次伤害时形成屏障（范围内实体动量归零）",
            // TODO(1.20.1-port): 依赖 api/SkillTriggerType、api/SkillCastMode、api/ISkillItem、
            //   tool/skill/SkillRanges
            java.util.EnumSet.of(net.minecraft.client.yiz.api.SkillTriggerType.ACTIVATE,
                                 net.minecraft.client.yiz.api.SkillTriggerType.TRIGGER),
            ctx -> {
                var player = ctx.player();
                if (ctx.triggerType() == net.minecraft.client.yiz.api.SkillTriggerType.TRIGGER) {
                    // 开关型：周期性屏障——把范围内实体的全部动量归零（setDeltaMovement(0,0,0)）。
                    // 不建窗口、不持久化，每次伤害当场执行。
                    double r = net.minecraft.client.yiz.tool.skill.SkillRanges.get(player, 4.0, "knockback");
                    var pos = player.position();
                    var level = player.level();
                    for (var e : level.getEntities(player,
                            new net.minecraft.world.phys.AABB(pos.x - r, pos.y - r, pos.z - r,
                                pos.x + r, pos.y + r, pos.z + r))) {
                        if (e instanceof net.minecraft.world.entity.LivingEntity && e != player) {
                            e.setDeltaMovement(0, 0, 0);
                            e.hurtMarked = true;
                        }
                    }
                } else {
                    // 开关型（TOGGLE）不建击退窗口——其 TRIGGER 分支负责动量归零。
                    // 若同时建窗口，tickLeizhenqianli 的外推会覆盖 TRIGGER 的归零，
                    // 导致只看到击退看不到"走一步被归0卡一下"。
                    if (ctx.skillItem().getItem() instanceof ISkillItem si
                        && si.getCastMode(ctx.skillItem()) == net.minecraft.client.yiz.api.SkillCastMode.TOGGLE) {
                        return;
                    }
                    // 按键型（INSTANT / CONTINUOUS / CHARGE）：建1秒击退窗口
                    // （由 tickLeizhenqianli 每2tick扫实体击退，每敌仅退一次）
                    var pd = player.getPersistentData();
                    pd.putLong("yiz:lzqll_kb_t", player.level().getGameTime() + 20);
                    pd.putString("yiz:lzqll_kb_d", "");
                }
            });

        register("benleixi", "奔雷袭",
            "按键型技能释放时创建0.8秒AoE窗口；开关型技能每次伤害时续期5秒窗口（持续AoE，每敌仅伤一次）",
            // TODO(1.20.1-port): 依赖 api/SkillTriggerType、api/SkillCastMode、api/ISkillItem、
            //   network/S2CBenleixiWindowPayload
            java.util.EnumSet.of(net.minecraft.client.yiz.api.SkillTriggerType.ACTIVATE,
                                 net.minecraft.client.yiz.api.SkillTriggerType.TRIGGER),
            ctx -> {
                var pd = ctx.player().getPersistentData();
                if (ctx.triggerType() == net.minecraft.client.yiz.api.SkillTriggerType.ACTIVATE) {
                    // 按键型（INSTANT/CONTINUOUS/CHARGE）：1.4 秒窗口；开关型（TOGGLE）：5 秒窗口
                    boolean isToggle = ctx.skillItem().getItem() instanceof ISkillItem si
                        && si.getCastMode(ctx.skillItem()) == net.minecraft.client.yiz.api.SkillCastMode.TOGGLE;
                    int duration = isToggle ? 5 * 20 : 16; // 5s : 0.8s
                    pd.putLong("yiz:benleixi_t", ctx.player().level().getGameTime() + duration);
                    pd.putString("yiz:benleixi_d", ""); // 仅新建时清空已伤害集合
                    // 同步窗口到客户端 → AutoAttackMixin 自动蓄力攻击
                    net.minecraft.client.yiz.network.S2CBenleixiWindowPayload.sendWindowStart(ctx.player(), duration);
                } else {
                    // TRIGGER：开关型每次伤害续期 5 秒窗口
                    pd.putLong("yiz:benleixi_t", ctx.player().level().getGameTime() + 5 * 20);
                    net.minecraft.client.yiz.network.S2CBenleixiWindowPayload.sendWindowStart(ctx.player(), 5 * 20);
                }
            });

        register("pili", "霹雳",
            "获得100%暴击率，4次攻击后移除",
            ctx -> addAttackModifier(ctx.player(), "yiz:pili",
                net.minecraft.client.yiz.attribute.YizAttributes.CRIT_RATE, 100.0, 4));

        register("leixiaoshan", "雷啸闪",
            "获得20%攻击速度持续5秒，可叠加3层至60%延至15秒",
            // TODO(1.20.1-port): 依赖 attribute/YizAttributes.COOLDOWN_REDUCTION（见 applyLeixiaoshan）
            ctx -> addLeixiaoshan(ctx.player()));

        register("pozhenjinshen", "破阵金身",
            "护盾获取频率提升100%（间隔减半：原4tick→2tick）",
            ctx -> ctx.player().getPersistentData().putBoolean("yiz:pozhenjinshen", true));

        register("leishen", "雷神",
            "每次攻击时额外触发1次相当于原伤害80%的连击",
            ctx -> {
                // 雷神是纯被动，标签激活即生效（由 LivingEntityMixin 读取）
                // 这里不需要额外操作，mixin 会检测标签是否激活
            });
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助：计时攻击修饰器（无影击/霹雳通用）
    // ═══════════════════════════════════════════════════════════

    /**
     * 给玩家添加一个持续 N 次攻击的属性修饰器。
     * <p>1.20.1 差异：修饰符 id 用 {@link UUID}（由 key 派生确定性 UUID），
     * 属性以 {@code RegistryObject<Attribute>} 传入，内部 {@code .get()} 取值
     * （与 EntityAttributeGate 约定一致）。</p>
     */
    private static void addAttackModifier(ServerPlayer player, String key,
                                          net.minecraftforge.registries.RegistryObject<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                                          double value, int attacks) {
        var inst = player.getAttribute(attr.get());
        if (inst == null) return;
        UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        inst.removeModifier(id);
        inst.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
            id, key, value, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
        player.getPersistentData().putInt(key + "_n", attacks);
    }

    /** 由 onPlayerAttack 调用：消费攻击计数并移除过期修饰器。 */
    private static void tickAttackModifier(ServerPlayer player, String key,
                                           net.minecraftforge.registries.RegistryObject<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        var pd = player.getPersistentData();
        int n = pd.getInt(key + "_n");
        if (n <= 0) return;
        n--;
        if (n <= 0) {
            pd.remove(key + "_n");
            var inst = player.getAttribute(attr.get());
            if (inst != null) inst.removeModifier(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
        } else {
            pd.putInt(key + "_n", n);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  雷啸闪：叠加 + 计时衰减
    // ═══════════════════════════════════════════════════════════

    private static void addLeixiaoshan(ServerPlayer player) {
        var pd = player.getPersistentData();
        int stacks = pd.getInt("yiz:leixiaoshan_s");
        if (stacks < 3) stacks++;
        pd.putInt("yiz:leixiaoshan_s", stacks);
        // 每次叠加延长 5 秒
        pd.putLong("yiz:leixiaoshan_t", player.level().getGameTime() + 5 * 20);
        applyLeixiaoshan(player);
    }

    public static void tickLeixiaoshan(ServerPlayer player) {
        var pd = player.getPersistentData();
        int stacks = pd.getInt("yiz:leixiaoshan_s");
        if (stacks <= 0) return;
        if (player.level().getGameTime() >= pd.getLong("yiz:leixiaoshan_t")) {
            stacks--;
            if (stacks <= 0) {
                pd.remove("yiz:leixiaoshan_s");
                pd.remove("yiz:leixiaoshan_t");
                var inst = player.getAttribute(net.minecraft.client.yiz.attribute.YizAttributes.COOLDOWN_REDUCTION.get());
                if (inst != null) inst.removeModifier(UUID.nameUUIDFromBytes("yiz:leixiaoshan".getBytes(StandardCharsets.UTF_8)));
                return;
            }
            pd.putInt("yiz:leixiaoshan_s", stacks);
            pd.putLong("yiz:leixiaoshan_t", player.level().getGameTime() + 5 * 20);
        }
        applyLeixiaoshan(player);
    }

    private static void applyLeixiaoshan(ServerPlayer player) {
        int stacks = player.getPersistentData().getInt("yiz:leixiaoshan_s");
        double cdr = stacks * 20.0; // 20/40/60% 攻击速度
        // TODO(1.20.1-port): 依赖 attribute/YizAttributes.COOLDOWN_REDUCTION（1.21.1 注册 id "cooldown_reduction"）
        var inst = player.getAttribute(net.minecraft.client.yiz.attribute.YizAttributes.COOLDOWN_REDUCTION.get());
        if (inst == null) return;
        UUID id = UUID.nameUUIDFromBytes("yiz:leixiaoshan".getBytes(StandardCharsets.UTF_8));
        inst.removeModifier(id);
        inst.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
            id, "yiz:leixiaoshan", cdr, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION));
    }

    // ═══════════════════════════════════════════════════════════
    //  攻击后处理（由 LivingEntityMixin 调用）
    // ═══════════════════════════════════════════════════════════

    public static void onPlayerAttack(ServerPlayer player) {
        var pd = player.getPersistentData();
        // 无影击：CR 修饰器 + 攻击计数消费
        // TODO(1.20.1-port): 依赖 attribute/YizAttributes.COOLDOWN_REDUCTION
        tickAttackModifier(player, "yiz:wuyingji", net.minecraft.client.yiz.attribute.YizAttributes.COOLDOWN_REDUCTION);
        // 霹雳：CRIT_RATE 修饰器 + 暴击标记（供 LivingEntityMixin 伤害阶段读取）
        tickAttackModifier(player, "yiz:pili", net.minecraft.client.yiz.attribute.YizAttributes.CRIT_RATE);
        // 如果还有霹雳次数，标记本次攻击为暴击
        if (pd.getInt("yiz:pili_n") > 0) pd.putBoolean("yiz:pili_crit", true);
    }

    // ═══════════════════════════════════════════════════════════
    //  奔雷袭：持续 AoE（每 tick 检测新敌人）
    // ═══════════════════════════════════════════════════════════

    public static void tickBenleixi(ServerPlayer player) {
        var pd = player.getPersistentData();
        if (!pd.contains("yiz:benleixi_t")) return;
        // 兜底：标签来源（技能）已卸载 → 立即清 AoE 窗口，防止残留伤害
        // TODO(1.20.1-port): 依赖 api/EffectSources.tagActive
        if (!net.minecraft.client.yiz.api.EffectSources.tagActive(player, "benleixi")) {
            pd.remove("yiz:benleixi_t");
            pd.remove("yiz:benleixi_d");
            // TODO(1.20.1-port): 依赖 network/S2CBenleixiWindowPayload.sendWindowEnd
            net.minecraft.client.yiz.network.S2CBenleixiWindowPayload.sendWindowEnd(player);
            return;
        }
        long now = player.level().getGameTime();
        long end = pd.getLong("yiz:benleixi_t");
        if (now > end) {
            pd.remove("yiz:benleixi_t");
            pd.remove("yiz:benleixi_d");
            // TODO(1.20.1-port): 依赖 network/S2CBenleixiWindowPayload.sendWindowEnd
            net.minecraft.client.yiz.network.S2CBenleixiWindowPayload.sendWindowEnd(player);
            return;
        }
        var level = player.level();
        var pos = player.position();
        double r = 6.0;
        double atkDmg = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        String damaged = pd.getString("yiz:benleixi_d");
        java.util.Set<String> set = new java.util.HashSet<>();
        if (!damaged.isEmpty()) java.util.Collections.addAll(set, damaged.split(","));
        float heal = 0;
        for (var e : level.getEntities(player,
                new net.minecraft.world.phys.AABB(pos.x - r, pos.y - r, pos.z - r,
                    pos.x + r, pos.y + r, pos.z + r))) {
            if (e instanceof net.minecraft.world.entity.LivingEntity le && e != player) {
                String uid = le.getUUID().toString();
                if (set.add(uid)) {
                    le.invulnerableTime = 0;
                    le.hurt(player.damageSources().mobAttack(player), (float) atkDmg);
                    heal += (float) atkDmg * 0.1f;
                }
            }
        }
        pd.putString("yiz:benleixi_d", String.join(",", set));
        if (heal > 0) player.heal(heal);
    }

    // ═══════════════════════════════════════════════════════════
    //  雷震千里：1秒击退窗口（标签侧实例，由 onPlayerTick 驱动）
    // ═══════════════════════════════════════════════════════════

    /**
     * 雷震千里击退窗口：ACTIVATE 触发后写入 1 秒时间戳，此方法每 tick 调用。
     * <p>每 {@code SKILL_INTERVAL} 加速后的间隔（基础 2tick）扫描范围内实体，
     * 已击退过的（UUID 去重）不再击退；窗口过期自清。</p>
     */
    public static void tickLeizhenqianli(ServerPlayer player) {
        var pd = player.getPersistentData();
        if (!pd.contains("yiz:lzqll_kb_t")) return;
        // 兜底：标签来源（技能）已卸载 → 立即清窗口，防止残留击退
        // TODO(1.20.1-port): 依赖 api/EffectSources.tagActive
        if (!net.minecraft.client.yiz.api.EffectSources.tagActive(player, "leizhenqianli")) {
            pd.remove("yiz:lzqll_kb_t");
            pd.remove("yiz:lzqll_kb_d");
            return;
        }
        long now = player.level().getGameTime();
        long end = pd.getLong("yiz:lzqll_kb_t");
        if (now > end) {
            pd.remove("yiz:lzqll_kb_t");
            pd.remove("yiz:lzqll_kb_d");
            return;
        }
        // 间隔：基础 2tick，受 SKILL_INTERVAL 加速率影响（用窗口起始时间算 elapsed）
        long start = end - 20;
        // TODO(1.20.1-port): 依赖 tool/skill/SkillIntervals.get
        double interval = net.minecraft.client.yiz.tool.skill.SkillIntervals.get(player, 2, "knockback");
        long elapsed = now - start;
        if (elapsed % Math.max(1, (long) interval) != 0) return;

        // TODO(1.20.1-port): 依赖 tool/skill/SkillRanges.get
        double r = net.minecraft.client.yiz.tool.skill.SkillRanges.get(player, 6.0, "knockback");
        var level = player.level();
        var pos = player.position();
        String damaged = pd.getString("yiz:lzqll_kb_d");
        java.util.Set<String> set = new java.util.HashSet<>();
        if (!damaged.isEmpty()) java.util.Collections.addAll(set, damaged.split(","));
        for (var e : level.getEntities(player,
                new net.minecraft.world.phys.AABB(pos.x - r, pos.y - r, pos.z - r,
                    pos.x + r, pos.y + r, pos.z + r))) {
            if (e instanceof net.minecraft.world.entity.LivingEntity && e != player) {
                String uid = e.getUUID().toString();
                if (set.add(uid)) {
                    double dx = e.getX() - pos.x;
                    double dz = e.getZ() - pos.z;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist < 0.1) dist = 0.1;
                    double power = 2.0; // 击退力度
                    e.setDeltaMovement(e.getDeltaMovement().add(dx / dist * power, 0.4, dz / dist * power));
                    e.hurtMarked = true;
                }
            }
        }
        pd.putString("yiz:lzqll_kb_d", String.join(",", set));
    }

    // ═══════════════════════════════════════════════════════════
    //  破阵金身：ACTIVATE 时挂 transient 标记 yiz:pozhenjinshen，
    //  由雷鸣电甲 onTick 护盾段读取决定 interval（4→2tick）。
    //  原错误的 tickMingyin（每tick回满）已删除——那从不是设计意图。
    // ═══════════════════════════════════════════════════════════

    public static boolean isTagActive(ServerPlayer player, String tagKey) {
        // TODO(1.20.1-port): 依赖 editor/SkillConfigStorage、api/IEnhanceable、editor/EnhanceEntry
        var data = SkillConfigStorage.get(player.getUUID());
        if (data == null) return false;
        ItemStack[] skills = { data.bigLoad().getItem(0),
            data.skillLoad().getItem(0), data.skillLoad().getItem(1), data.skillLoad().getItem(2) };
        for (ItemStack skill : skills) {
            if (skill.isEmpty() || !(skill.getItem() instanceof IEnhanceable e)) continue;
            var entries = e.getEnhanceEntries(skill, player);
            int[] levels = SkillConfigStorage.getEnhanceLevels(skill);
            for (int i = 0; i < Math.min(entries.size(), levels.length); i++) {
                if (entries.get(i) instanceof EnhanceEntry.Tag t && t.key().equals(tagKey) && levels[i] > 0)
                    return true;
            }
        }
        return false;
    }
}
