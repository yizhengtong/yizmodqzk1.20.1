package net.minecraft.client.yiz.creature;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组件注册表 + 内置组件定义。
 *
 * <p>所有生物配置组件在这里登记，保证 id 唯一、类型安全、可被指令补全与数据包反查。
 * 沿用既有字符串 key 作为 id path（如 {@code yizmodqzk:knockback_immunity}），
 * 便于从旧的裸字符串效果体系平滑迁移。</p>
 */
public final class CreatureComponents {

    private static final String NS = "yizmodqzk";

    private static final Map<ResourceLocation, ComponentType<?>> REGISTRY = new ConcurrentHashMap<>();
    /** 保持注册顺序，供指令补全等场景稳定输出。 */
    private static final Map<ResourceLocation, ComponentType<?>> ORDERED = new LinkedHashMap<>();

    private CreatureComponents() {}

    // ==================== 注册 ====================

    /** 注册一个组件类型；同 id 重复注册返回首次注册的实例。 */
    public static synchronized <T> ComponentType<T> register(String path, Codec<T> codec, T defaultValue) {
        return register(new ResourceLocation(NS, path), codec, defaultValue);
    }

    @SuppressWarnings("unchecked")
    public static synchronized <T> ComponentType<T> register(ResourceLocation id, Codec<T> codec, T defaultValue) {
        ComponentType<?> existing = REGISTRY.get(id);
        if (existing != null) return (ComponentType<T>) existing;
        ComponentType<T> type = new ComponentType<>(id, codec, defaultValue);
        REGISTRY.put(id, type);
        ORDERED.put(id, type);
        return type;
    }

    /** 按 id 反查组件类型；未注册返回 null。 */
    public static ComponentType<?> byId(ResourceLocation id) {
        return id == null ? null : REGISTRY.get(id);
    }

    /** 按 id 取布尔组件类型（数据包 effects 列表解析用）；非布尔组件返回 null。 */
    @SuppressWarnings("unchecked")
    public static ComponentType<Boolean> booleanEffect(String id) {
        ComponentType<?> type = byId(id);
        if (type == null) return null;
        return type.defaultValue() instanceof Boolean ? (ComponentType<Boolean>) type : null;
    }

    /** 按 id 字符串反查（容错：不带命名空间时按本模组命名空间补全，且忽略大小写差异）。 */
    public static ComponentType<?> byId(String id) {
        if (id == null || id.isEmpty()) return null;
        ResourceLocation rl = id.indexOf(':') >= 0
            ? ResourceLocation.tryParse(id)
            : new ResourceLocation(NS, id);
        return byId(rl);
    }

    /** 全部已注册组件（注册顺序）。 */
    public static Collection<ComponentType<?>> all() {
        return ORDERED.values();
    }

    // ==================== 内置：生物效果 ====================

    /** 免清除：拦外力把实体从世界清除/移除。 */
    public static final ComponentType<Boolean> CLEAR_IMMUNITY =
        register("clear_immunity", Codec.BOOL, Boolean.FALSE);
    /** 拉回：实体被清除后自愈回填 + 快照复活。 */
    public static final ComponentType<Boolean> PULLBACK =
        register("pullback", Codec.BOOL, Boolean.FALSE);
    /** 免传送：坐标变更门禁 + 字段级位置恢复。 */
    public static final ComponentType<Boolean> TELEPORT_IMMUNITY =
        register("teleport_immunity", Codec.BOOL, Boolean.FALSE);
    /** 免药水：负面状态免疫 + 每 tick 清状态。 */
    public static final ComponentType<Boolean> POTION_IMMUNITY =
        register("potion_immunity", Codec.BOOL, Boolean.FALSE);
    /** 免击退：knockback / setDeltaMovement 门禁。 */
    public static final ComponentType<Boolean> KNOCKBACK_IMMUNITY =
        register("knockback_immunity", Codec.BOOL, Boolean.FALSE);
    /** 免物理：卡方块/流体推动/水中减速。 */
    public static final ComponentType<Boolean> PHYSICAL_IMMUNITY =
        register("physical_immunity", Codec.BOOL, Boolean.FALSE);
    /** 免骑乘：不可被骑乘/上载具。 */
    public static final ComponentType<Boolean> RIDE_IMMUNITY =
        register("ride_immunity", Codec.BOOL, Boolean.FALSE);

    // ==================== 内置：属性 / 战斗 ====================

    /** 属性组件：属性注册名 → 数值。 */
    public static final ComponentType<Map<ResourceLocation, Double>> ATTRIBUTES =
        register("attributes", Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE), Map.of());

    /** 战斗参数组件：攻击间隔 / 攻击范围 / 技能。 */
    public static final ComponentType<CombatSpec> COMBAT =
        register("combat", CombatSpec.CODEC, CombatSpec.EMPTY);

    /** 形态组件：多形态生物的形态表（每个形态自带变化 + 进入下一形态条件 + 回退条件）。 */
    public static final ComponentType<List<PhaseSpec>> PHASES =
        register("phases", PhaseSpec.LIST_CODEC, List.of());
}
