package net.minecraft.client.yiz.tool.attribute;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 属性标准化守护（1.20.1 移植版）— 防外部 mod 篡改实体属性。
 *
 * <p>辖界者等 yiz 家族实体在 {@code applyEntityAttributes()} 时向本类注册「标准属性表」
 * （每个属性的标准 base 值 + 标准 prot_ modifier 值）。之后周期性校验：</p>
 * <ol>
 *   <li>属性当前值 ≠ 标准值 → 进入 ；</li>
 *   <li>该属性是否经「编辑器」编辑（{@link #markEdited} 标记）→ 是则放行（用户有意为之）；</li>
 *   <li>未经过编辑器 → 判定为外部篡改 → 还原到标准值（清非家族 modifier + 重设 base + 重设 prot_）。</li>
 * </ol>
 *
 * <p>与 {@link EntityAttributeGate} 分工：Gate 管「写入口鉴权」（prot_ modifier 移除拦截），
 * 本类管「存量审计」（base 值被改 / 外部 modifier 的兜底还原）。</p>
 */
public final class AttributeStandardizer {

    private AttributeStandardizer() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 标准属性记录：标准 base 值 + 标准 prot_ modifier 值。 */
    private record Standard(Attribute attr, String idKey, double baseValue, double protValue) {}

    /** 标准属性表：实体 UUID → (属性 → 标准)。 */
    private static final Map<UUID, Map<Attribute, Standard>> STANDARDS = new ConcurrentHashMap<>();

    /** 编辑器编辑标记：实体 UUID → 已编辑的 idKey 集合（标记后该属性放行，不再还原）。 */
    private static final Map<UUID, Set<String>> EDITED = new ConcurrentHashMap<>();

    /** 上次检查 tick（节流）。 */
    private static final Map<UUID, Integer> LAST_CHECK = new ConcurrentHashMap<>();

    /** 检查周期（tick）。 */
    private static final int ENFORCE_INTERVAL = 20;
    private static final float EPSILON = 0.001f;

    /** 家族自身 modifier name 前缀（prot_ / entity_ / 狂暴移速等），还原时豁免。 */
    private static final String[] FAMILY_MODIFIER_PREFIXES = { "yizmodqzk:", "yizxianmod:" };

    //  注册 / 标记 / 清理 

    /**
     * 注册某属性的标准值。在 {@code applyEntityAttributes()} 里对每个受管属性调用。
     * 标准值 = 当前 instance 的 base（含难度缩放）+ protValue（prot_ modifier 值，无则传 0）。
     */
    public static void registerStandard(LivingEntity entity, Attribute attr, String idKey, double protValue) {
        if (entity == null || attr == null) return;
        AttributeInstance inst = entity.getAttribute(attr);
        if (inst == null) return;
        STANDARDS.computeIfAbsent(entity.getUUID(), k -> new ConcurrentHashMap<>())
            .put(attr, new Standard(attr, idKey, inst.getBaseValue(), protValue));
    }

    /** 标记某属性经编辑器合法编辑（编辑器写属性时调用）；标记后该属性永不被还原。 */
    public static void markEdited(LivingEntity entity, String idKey) {
        if (entity == null || idKey == null) return;
        EDITED.computeIfAbsent(entity.getUUID(), k -> ConcurrentHashMap.newKeySet()).add(idKey);
    }

    /** 查询某属性是否经编辑器合法编辑（豁免还原）。供 ConductionCapVault 等权威表防误还原。 */
    public static boolean isEdited(LivingEntity entity, String idKey) {
        if (entity == null || idKey == null) return false;
        Set<String> s = EDITED.get(entity.getUUID());
        return s != null && s.contains(idKey);
    }

    /** 实体移除/死亡时清理，防止表残留。 */
    public static void cleanup(LivingEntity entity) {
        if (entity == null) return;
        STANDARDS.remove(entity.getUUID());
        EDITED.remove(entity.getUUID());
        LAST_CHECK.remove(entity.getUUID());
    }

    //  周期检查 

    /** 每 tick 调用（服务端），按间隔触发审计。 */
    public static void tick(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return;
        if (!STANDARDS.containsKey(entity.getUUID())) return;
        int last = LAST_CHECK.computeIfAbsent(entity.getUUID(), k -> 0);
        if (entity.tickCount - last < ENFORCE_INTERVAL) return;
        LAST_CHECK.put(entity.getUUID(), entity.tickCount);
        enforce(entity);
    }

    private static void enforce(LivingEntity entity) {
        Map<Attribute, Standard> stds = STANDARDS.get(entity.getUUID());
        if (stds == null) return;
        Set<String> editedSet = EDITED.get(entity.getUUID());
        for (Standard std : stds.values()) {
            AttributeInstance inst = entity.getAttribute(std.attr());
            if (inst == null) continue;
            double expected = std.baseValue() + std.protValue();
            if (Math.abs(inst.getValue() - expected) < EPSILON) continue;          // 符合标准
            if (editedSet != null && editedSet.contains(std.idKey())) continue;    // 编辑器编辑过，放行
            restore(entity, inst, std);                                            // 外部篡改，还原
        }
    }

    private static void restore(LivingEntity entity, AttributeInstance inst, Standard std) {
        try {
            // 1. 移除外部 modifier（保留家族自身：prot_ / entity_ / 狂暴移速）
            List<UUID> toRemove = new ArrayList<>();
            for (AttributeModifier m : inst.getModifiers()) {
                if (!isFamilyModifier(m)) toRemove.add(m.getId());
            }
            for (UUID id : toRemove) {
                try {
                    inst.removeModifier(id);
                } catch (Throwable ignored) {}
            }
            // 2. 重设 base
            inst.setBaseValue(std.baseValue());
            // 3. 重设 prot_ modifier（经 Gate 受保护写入）
            ResourceLocation rl = ForgeRegistries.ATTRIBUTES.getKey(std.attr());
            if (rl == null) return;
            RegistryObject<Attribute> ro = RegistryObject.create(rl, ForgeRegistries.ATTRIBUTES);
            if (std.protValue() != 0) {
                EntityAttributeGate.set(entity, ro, std.idKey(), std.protValue());
            } else {
                EntityAttributeGate.remove(entity, ro, std.idKey());
            }
            LOGGER.warn("[AttributeStandardizer] 检测到外部篡改，还原属性 {} 至标准值 {} (uuid={})",
                std.idKey(), std.baseValue() + std.protValue(), entity.getUUID());
        } catch (Throwable t) {
            LOGGER.warn("[AttributeStandardizer] 还原属性 {} 失败: {}", std.idKey(), t.getMessage());
        }
    }

    private static boolean isFamilyModifier(AttributeModifier m) {
        String name = m.getName();
        for (String p : FAMILY_MODIFIER_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }
}
