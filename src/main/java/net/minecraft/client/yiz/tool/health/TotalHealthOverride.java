package net.minecraft.client.yiz.tool.health;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/**
 * 通用「扫描 → 判定 → 修改」改血管道（对任意实体生效，无任何模组针对）。
 *
 * <p>三阶段：</p>
 * <ol>
 *   <li><b>scan（扫描）</b>：收集实体在内存中的全部候选 —— 实体层级字段、
 *       数值/前缀串/布尔 DataItem、可达对象图数值字段、静态藏血 Map、NBT 持久化键；</li>
 *   <li><b>judge（判定）</b>：主槽由 {@link EntityHealthLocator} 行为定位
 *       （forward/inverse/codec/通道，全部行为验证 + 存储解码）；
 *       其余数值候选「与当前逻辑血量同值」→ 判定为<b>健康镜像/参考值</b>（同步写，
 *       让模组的拉回依据与血量一致）；布尔候选由判死标记探测与
 *       {@link GateHunt} 权威门猎杀处理；</li>
 *   <li><b>modify（修改）</b>：主槽 + 全部镜像同步写目标值（纯减法）→ 门控击穿 →
 *       目标 ≤ 0 触发模组<b>正常</b>死亡流程 → 2 tick 写回验证。</li>
 * </ol>
 *
 * <p>判定全部基于「行为探针 + 值域相等 + 写后验证」，不依赖类名/字段名/包名；
 * 声明式覆盖（{@code health_overrides.json}）仅作行为探测不可达时的可选补充。</p>
 */
public final class TotalHealthOverride {

    private TotalHealthOverride() {}

    // ==================== 入口：一次性执行三阶段 ====================

    /**
     * 对目标执行通用改血管道。
     *
     * @return true 表示本次伤害已由管道处理；false 调用方应走旧链路
     */
    public static boolean apply(LivingEntity attacker, LivingEntity entity, float amount) {
        if (entity == null || amount <= 0) return false;
        if (entity.level().isClientSide()) return false;
        // 清除陈旧梦魇死亡累积 + 重置 delta 通道：
        // agent specialGetHealth/isAlive 按 delta 钳制读值；死亡链残留的 delta(-inf)
        // 会让 agent 包装的 getHealth 与存储真值不一致 → 模组每 tick 看门狗（按 getHealth
        // 推断血量）误判「被篡改」→ 把我们的写入拉回/重置。重置后 agent 读值即存储真值。
        EntityASMUtil.clearDreamAccum(entity);
        try {
            EntityASMUtil.setHealthDelta(entity, 0F);
        } catch (Throwable ignored) {}

        // —— judge：当前逻辑血量（主槽存储解码优先，免疫 agent/delta/累积干扰）——
        double current = judgeCurrentHealth(entity);
        if (!Double.isFinite(current)) return false;
        if (current <= 0) return true;                 // 已死，视为处理完成
        double target = Math.max(0, current - amount); // 纯减法
        LOGGER.info("[TotalOverride] {} 当前血={} amount={} → 目标={}",
            entity.getClass().getName(), current, amount, target);

        // —— modify：主槽 + 镜像同步 + 门控击穿 + 正常死亡 + 写回验证 ——
        boolean any = modify(entity, target, current);
        if (!any) return false;

        smashGates(entity);
        if (target <= 0) {
            LOGGER.warn("[TotalOverride] {} 触发正常死亡流程（目标=0）", entity.getClass().getName());
            // 允许 +25 tick 后对「复活型/拒死型」实体强制深层反注册（先走正常流程，最后一击兜底）
            EntityASMUtil.markForceRemoveAllowed(entity.getId());
            EntityASMUtil.dreamDeathblow(attacker, entity);
            // 死亡链会置 delta=-inf（agent 钳制用）；对复活型实体立即还原，
            // 防看门狗按 agent 包装的 getHealth 误判篡改 → 把复活体重置回满血
            try {
                EntityASMUtil.setHealthDelta(entity, 0F);
            } catch (Throwable ignored) {}
            // 拒死型实体：普通 boolean「死亡/移除放行开关」被门控（die/remove 空转拦截）。
            // 行为探测并开门 → 重走目标自己的 die → 掉落 + 移除（规则 C）；未命中无副作用。
            if (!entity.isRemoved()) {
                try {
                    DamageSource ds = attacker != null
                            ? entity.damageSources().mobAttack(attacker)
                            : entity.damageSources().genericKill();
                    RemoveGateAccessor.tamperToAllowDeath(entity, ds);
                } catch (Throwable ignored) {}
            }
        }
        GateHunt.verifyAndHunt(entity, target);        // 2 tick 写回验证 + 权威门控猎杀
        return true;
    }

    // ==================== 阶段二：判定 ====================

    /** 判定当前逻辑血量：主槽存储解码 → 兜底 getHealth。 */
    private static double judgeCurrentHealth(LivingEntity entity) {
        try {
            Double v = EntityHealthLocator.readLocated(entity);
            if (v != null && Double.isFinite(v)) return v;
        } catch (Throwable ignored) {}
        try {
            Double v = ExternalHealthStore.readHealth(entity);
            if (v != null && Double.isFinite(v)) return v;
        } catch (Throwable ignored) {}
        try {
            return entity.getHealth();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    // ==================== 阶段三：修改 ====================

    /**
     * 统一修改：主槽写目标 + 全部「健康镜像/参考值」同步写目标 + vanilla 通道（无槽实体）。
     * 返回是否改到任何表征。
     */
    private static boolean modify(LivingEntity entity, double target, double current) {
        boolean any = false;
        int[] counts = new int[6];   // 主槽/数值通道/字符串通道/藏血Map/图字段/NBT
        boolean hasSlot = EntityHealthLocator.locate(entity) != null;

        // 1. 主槽（行为定位的 forward/inverse/codec/通道/声明式槽）
        try {
            if (hasSlot) {
                EntityHealthLocator.writeLocated(entity, target);
                any = true;
                counts[0]++;
            }
        } catch (Throwable ignored) {}

        // 2. 数值 DataItem 镜像（与当前血量同值 → 同步写；有槽实体排除 vanilla 显示通道）
        try {
            DirectHealthFallback.forEachNumericItem(entity, (acc, value, item) -> {
                if (hasSlot && DirectHealthFallback.VANILLA_HEALTH_ACCESSOR != null
                        && acc.getId() == DirectHealthFallback.VANILLA_HEALTH_ACCESSOR.getId()) return;
                double v = value.doubleValue();
                if (close(v, current)) {
                    DirectHealthFallback.setNumericChannelValue(entity, acc, coerce(target, value), true);
                    counts[1]++;
                }
            });
        } catch (Throwable ignored) {}

        // 3. 前缀串 DataItem 镜像（与当前血量同值 → 同步写）
        try {
            DirectHealthFallback.forEachStringItem(entity, (acc, value, item) -> {
                double[] parsed = EntityHealthLocator.parseNumberFromString(value);
                if (parsed != null && close(parsed[0], current)) {
                    String rebuilt = EntityHealthLocator.rebuildStringNumber(value, parsed, target);
                    DirectHealthFallback.setStringChannelValue(entity, acc, rebuilt, true);
                    counts[2]++;
                }
            });
        } catch (Throwable ignored) {}

        // 4. 静态藏血 Map（K=实体/ID/UUID、V=数值；unreflectSpecial 绕过写方法鉴权）
        try {
            Float hp = HealthMapRegistry.readHealth(entity);
            if (hp != null) {
                HealthMapRegistry.tamperHealth(entity, (float) target);
                any = true;
                counts[3]++;
            }
        } catch (Throwable ignored) {}

        // 4b. 外部静态单例藏血 Map（K=UUID/实体id、V=数值或密码对象；下钻 + ARX 密码反解写真实血）
        try {
            if (ExternalHealthStore.writeHealth(entity, target)) {
                any = true;
                counts[3]++;
            }
        } catch (Throwable ignored) {}

        // 5. 可达对象图数值字段镜像（与当前血量同值 → 同步写，含实体自身层级字段）
        try {
            List<ValueRef> refs = ReachableGraphScanner.scan(entity);
            for (ValueRef ref : refs) {
                double v = ref.read();
                if (Double.isFinite(v) && close(v, current)) {
                    ref.write(target);
                    any = true;
                    counts[4]++;
                }
            }
        } catch (Throwable ignored) {}

        // 6. NBT 持久化数值键镜像（与当前血量同值 → 同步写，含模组参考值）
        try {
            CompoundTag tag = entity.getPersistentData();
            for (String key : tag.getAllKeys()) {
                byte type = tag.getTagType(key);
                if (!isNumericType(type)) continue;
                double v = tag.getDouble(key);
                if (Double.isFinite(v) && close(v, current)) {
                    writeNbtNumber(tag, key, type, target);
                    any = true;
                    counts[5]++;
                }
            }
        } catch (Throwable ignored) {}

        // 7. vanilla 通道：仅无槽实体（普通实体）写逻辑值；有槽实体的 vanilla 通道是
        //    模组自管的「显示通道」（按自身缩放比每 tick 重写），写逻辑值会互相覆盖致血条跳动
        if (!hasSlot) {
            try {
                if (DirectHealthFallback.VANILLA_HEALTH_ACCESSOR != null) {
                    DirectHealthFallback.setFloatChannelValue(entity,
                        DirectHealthFallback.VANILLA_HEALTH_ACCESSOR, (float) target, true);
                    any = true;
                }
            } catch (Throwable ignored) {}
        }

        if (WRITE_LOG.add(entity.getClass().getName())) {
            LOGGER.info("[TotalOverride] {} 表征扫描: 主槽={} 数值通道={} 字符串={} 藏血Map={} 图字段={} NBT={}",
                entity.getClass().getName(), counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]);
        }
        return any;
    }

    // ==================== 门控击穿 ====================

    /** 击穿死亡/移除门控：判死标记（行为探测）+ 声明式门控（可选补充）。 */
    private static void smashGates(LivingEntity entity) {
        try {
            DeathMarkerAccessor.tamperToDead(entity);   // 设 true → isAlive 翻 false 的判死标记
        } catch (Throwable ignored) {}
        try {
            for (HealthOverridesConfig.GateDecl decl : HealthOverridesConfig.gatesFor(entity)) {
                if (HealthOverridesConfig.applyGate(entity, decl)) {
                    LOGGER.info("[TotalOverride] 门控击穿 {} {}", decl.kind() + ":" + decl.name(), decl.value());
                }
            }
        } catch (Throwable ignored) {}
    }

    // ==================== 工具 ====================

    /** 数值近似相等（浮点容差：绝对 0.5 或相对 0.1%）。 */
    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= Math.max(0.5, Math.max(Math.abs(a), Math.abs(b)) * 0.001);
    }

    private static Number coerce(double d, Object ref) {
        if (ref instanceof Integer) return (int) Math.round(d);
        if (ref instanceof Long) return (long) Math.round(d);
        if (ref instanceof Double) return d;
        if (ref instanceof Short) return (short) Math.round(d);
        if (ref instanceof Byte) return (byte) Math.round(d);
        return (float) d;
    }

    private static boolean isNumericType(byte type) {
        return type == Tag.TAG_BYTE || type == Tag.TAG_SHORT || type == Tag.TAG_INT
                || type == Tag.TAG_LONG || type == Tag.TAG_FLOAT || type == Tag.TAG_DOUBLE;
    }

    private static void writeNbtNumber(CompoundTag tag, String key, byte type, double v) {
        switch (type) {
            case Tag.TAG_BYTE -> tag.putByte(key, (byte) v);
            case Tag.TAG_SHORT -> tag.putShort(key, (short) v);
            case Tag.TAG_INT -> tag.putInt(key, (int) v);
            case Tag.TAG_LONG -> tag.putLong(key, (long) v);
            case Tag.TAG_FLOAT -> tag.putFloat(key, (float) v);
            default -> tag.putDouble(key, v);
        }
    }

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;
    private static final java.util.Set<String> WRITE_LOG = java.util.concurrent.ConcurrentHashMap.newKeySet();
}
