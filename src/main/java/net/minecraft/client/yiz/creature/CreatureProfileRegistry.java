package net.minecraft.client.yiz.creature;

import net.minecraft.client.yiz.tool.attribute.AttributeStandardizer;
import net.minecraft.client.yiz.tool.attribute.EntityAttributeGate;
import net.minecraft.client.yiz.tool.effect.InstanceEffectState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生物原型注册表 —— 组件化配置的入口与解析中心。
 *
 * <p>两个来源写进同一张表：代码轨 {@link #register} 给默认值，数据包轨（后续接入）解析 JSON 后走同一入口。
 * 类级继承由 {@link CreatureProfile#parent()} 表达；实例差异继续由
 * {@link InstanceEffectState} 的组件补丁承载（棋子星级、指令编辑等）。</p>
 *
 * <p>解析优先级：<b>实例补丁 → 原型（含父链合并）→ 组件缺省值</b>。</p>
 */
public final class CreatureProfileRegistry {

    /** 代码轨原型（不可被数据包清除）。 */
    private static final Map<ResourceLocation, CreatureProfile> CODE_PROFILES = new ConcurrentHashMap<>();
    /** 数据包轨原型（重载时整体清空重建，同 id 优先于代码轨）。 */
    private static final Map<ResourceLocation, CreatureProfile> DATA_PROFILES = new ConcurrentHashMap<>();
    /** 原型 id → 已沿父链合并的组件集合（注册/覆盖时刷新）。 */
    private static final Map<ResourceLocation, ComponentMap> RESOLVED_PROFILES = new ConcurrentHashMap<>();
    /** 实体类 → 原型 id（沿类继承链向上查找最近绑定）。 */
    private static final Map<Class<?>, ResourceLocation> CLASS_BINDINGS = new ConcurrentHashMap<>();

    private CreatureProfileRegistry() {}

    // ==================== 注册 ====================

    /** 注册（或覆盖）一份代码轨原型。 */
    public static void register(CreatureProfile profile) {
        if (profile == null) return;
        CODE_PROFILES.put(profile.id(), profile);
        RESOLVED_PROFILES.remove(profile.id());
    }

    /** 注册（或覆盖）一份数据包轨原型。 */
    public static void registerData(CreatureProfile profile) {
        if (profile == null) return;
        DATA_PROFILES.put(profile.id(), profile);
        RESOLVED_PROFILES.remove(profile.id());
    }

    /** 清空数据包轨（重载前调用），代码轨不受影响。 */
    public static void clearData() {
        DATA_PROFILES.clear();
        RESOLVED_PROFILES.clear();
    }

    /** 按 id 取原型：数据包轨优先，其次代码轨。 */
    public static CreatureProfile profileById(ResourceLocation id) {
        if (id == null) return null;
        CreatureProfile p = DATA_PROFILES.get(id);
        return p != null ? p : CODE_PROFILES.get(id);
    }

    /** 把实体类绑定到某原型（类级继承：子类未绑定时沿父类查找）。 */
    public static void bindClass(Class<?> type, ResourceLocation profileId) {
        if (type == null || profileId == null) return;
        CLASS_BINDINGS.put(type, profileId);
    }

    /** 按实体类型取绑定 id；未绑定返回 null。 */
    public static ResourceLocation bindingOf(EntityType<?> type) {
        if (type == null) return null;
        return bindingOfClass(type.getBaseClass());
    }

    private static ResourceLocation bindingOfClass(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != LivingEntity.class; c = c.getSuperclass()) {
            ResourceLocation id = CLASS_BINDINGS.get(c);
            if (id != null) return id;
        }
        return null;
    }

    // ==================== 解析 ====================

    /** 实体对应的原型；无绑定或未注册返回 null。 */
    public static CreatureProfile profileFor(LivingEntity entity) {
        if (entity == null) return null;
        ResourceLocation id = bindingOfClass(entity.getClass());
        return id == null ? null : profileById(id);
    }

    /** 原型 id 对应的已合并组件集合（含父链，子覆盖父）。 */
    public static ComponentMap resolvedComponents(ResourceLocation profileId) {
        if (profileId == null) return ComponentMap.EMPTY;
        return RESOLVED_PROFILES.computeIfAbsent(profileId, id -> {
            CreatureProfile profile = profileById(id);
            if (profile == null) return ComponentMap.EMPTY;
            ComponentMap base = profile.parent() != null
                ? resolvedComponents(profile.parent())
                : ComponentMap.EMPTY;
            return merge(base, profile.components());
        });
    }

    /** 最终组件集合：原型的已合并结果 + 实例补丁。 */
    public static ComponentMap componentsOf(LivingEntity entity) {
        if (entity == null) return ComponentMap.EMPTY;
        ComponentMap base = ComponentMap.EMPTY;
        ResourceLocation id = bindingOfClass(entity.getClass());
        if (id != null) base = resolvedComponents(id);
        return InstanceEffectState.patchOf(entity).apply(base);
    }

    /** 取实体当前的战斗参数（实例补丁优先 → 原型 → 空）。 */
    public static CombatSpec combatOf(LivingEntity entity) {
        if (entity == null) return CombatSpec.EMPTY;
        ComponentPatch patch = InstanceEffectState.patchOf(entity);
        if (!patch.isRemoved(CreatureComponents.COMBAT)) {
            Object v = patch.added().get(CreatureComponents.COMBAT);
            if (v instanceof CombatSpec spec) return spec;
        }
        ResourceLocation id = bindingOfClass(entity.getClass());
        if (id != null) {
            CombatSpec spec = resolvedComponents(id).get(CreatureComponents.COMBAT);
            if (spec != null) return spec;
        }
        return CombatSpec.EMPTY;
    }

    /** 取实体当前的形态表（实例补丁优先 → 原型 → 空表），按 index 升序。 */
    public static java.util.List<PhaseSpec> phasesOf(LivingEntity entity) {
        if (entity == null) return java.util.List.of();
        java.util.List<PhaseSpec> result = null;
        ComponentPatch patch = InstanceEffectState.patchOf(entity);
        if (!patch.isRemoved(CreatureComponents.PHASES)) {
            Object v = patch.added().get(CreatureComponents.PHASES);
            if (v instanceof java.util.List<?> list) result = castPhases(list);
        }
        if (result == null) {
            ResourceLocation id = bindingOfClass(entity.getClass());
            if (id != null) result = resolvedComponents(id).get(CreatureComponents.PHASES);
        }
        if (result == null || result.isEmpty()) return java.util.List.of();
        java.util.List<PhaseSpec> sorted = new java.util.ArrayList<>(result);
        sorted.sort(java.util.Comparator.comparingInt(PhaseSpec::safeIndex));
        return sorted;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<PhaseSpec> castPhases(java.util.List<?> raw) {
        for (Object o : raw) {
            if (!(o instanceof PhaseSpec)) return null;
        }
        return (java.util.List<PhaseSpec>) raw;
    }

    // ==================== 应用 ====================

    /**
     * 把原型的属性组件写入实体（实体首个服务端 tick 调用，此时处于受信任调用栈）。
     *
     * <p>两种写入语义：</p>
     * <ul>
     *   <li>{@code yizmodqzk:} 自定义属性 → {@code prot_} 修饰符（保留其他来源的加成）；</li>
     *   <li>{@code minecraft:} 原版属性 → 直接接管 base 值（不再叠加难度缩放，配置值即最终值）。</li>
     * </ul>
     *
     * <p>两种都需同步 {@code AttributeStandardizer} 标准值：否则 20 tick 一轮的篡改审计
     * 会把这些配置值判为外部修改并还原。</p>
     */
    public static void apply(LivingEntity entity) {
        if (entity == null) return;
        ComponentMap map = componentsOf(entity);
        Map<ResourceLocation, Double> attrs = map.get(CreatureComponents.ATTRIBUTES);
        if (attrs == null || attrs.isEmpty()) return;
        for (Map.Entry<ResourceLocation, Double> entry : attrs.entrySet()) {
            ResourceLocation attrId = entry.getKey();
            Double value = entry.getValue();
            if (attrId == null || value == null) continue;
            Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(attrId);
            if (attr == null) continue;
            String idKey = idKeyFor(attrId);
            if ("yizmodqzk".equals(attrId.getNamespace())) {
                RegistryObject<Attribute> ro = RegistryObject.create(attrId, ForgeRegistries.ATTRIBUTES);
                EntityAttributeGate.set(entity, ro, idKey, value);
                AttributeStandardizer.registerStandard(entity, attr, idKey, value);
            } else {
                var inst = entity.getAttribute(attr);
                if (inst == null) continue;
                inst.setBaseValue(value);
                AttributeStandardizer.registerStandard(entity, attr, idKey, 0);
            }
        }
    }

    /** 属性 id 转标准表用的 idKey：原版属性去掉 {@code generic.} 等前缀（generic.max_health → max_health）。 */
    private static String idKeyFor(ResourceLocation attrId) {
        String path = attrId.getPath();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    // ==================== 工具 ====================

    private static ComponentMap merge(ComponentMap base, ComponentMap overlay) {
        if (overlay == null || overlay.isEmpty()) return base;
        ComponentMap result = base;
        for (ComponentType<?> type : overlay.keySet()) {
            result = putUnchecked(result, type, overlay.get(type));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> ComponentMap putUnchecked(ComponentMap map, ComponentType<T> type, Object value) {
        return map.with(type, (T) value);
    }
}
