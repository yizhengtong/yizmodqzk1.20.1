package net.minecraft.client.yiz.tool.effect;

import net.minecraft.client.yiz.creature.ComponentPatch;
import net.minecraft.client.yiz.creature.ComponentType;
import net.minecraft.client.yiz.creature.CreatureComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每实例每玩家效果隔离注册表（自走棋棋子效果模型基础）。
 *
 * <p>实体效果（免清除/拉回/免传送/免药水/免物理/免骑乘）不再是基类硬编码/全局静态开关，
 * 而是<b>每实体实例</b>一份状态：归属玩家 + 显式开启/关闭覆盖。判定顺序：</p>
 * <ol>
 *   <li>实例显式覆盖（组件补丁）优先；</li>
 *   <li>该实体类型的「基础效果」（{@link #registerBaseEffects}）；</li>
 *   <li>默认 false（基础形态，需玩家增强才开启）。</li>
 * </ol>
 *
 * <p>存储已从「enabled/disabled 裸字符串集合」重构为通用组件容器的
 * {@link ComponentPatch}：每个效果是一个布尔组件（见 {@link CreatureComponents}）。
 * 对外 API 与 NBT 格式（{@code yiz_effects}）保持不变，老存档可直接读取。</p>
 *
 * <p>归属校验：修改效果需操作者 == 实体归属玩家，或经
 * {@code EntityAttributeGate.isCallerTrusted()} 的本模组调用栈（指令/服务器）。
 * 玩家之间天然隔离：A 对 A 名下实例的增强不作用于 B 的实例。</p>
 */
public final class InstanceEffectState {

    /** 免清除（拦外力把实体从世界清除/移除/结构摘除 + 拒自然清除；存在性保护，正交于拉回）。 */
    public static final String CLEAR_IMMUNITY = "clear_immunity";
    /** 拉回（实体被清除后自愈回填 + 快照持久化复活；正交于免清除）。 */
    public static final String PULLBACK = "pullback";
    /** 免传送（坐标变更门禁 + 字段级位置恢复）。 */
    public static final String TELEPORT_IMMUNITY = "teleport_immunity";
    /** 免药水（负面状态免疫 + 每 tick 清状态）。 */
    public static final String POTION_IMMUNITY = "potion_immunity";
    /** 免击退（motionGate：knockback/setDeltaMovement）。 */
    public static final String KNOCKBACK_IMMUNITY = "knockback_immunity";
    /** 免物理（卡方块/流体推动/水中减速）。 */
    public static final String PHYSICAL_IMMUNITY = "physical_immunity";
    /** 免骑乘（不可被骑乘/上载具）。 */
    public static final String RIDE_IMMUNITY = "ride_immunity";

    /** 效果 id → 布尔组件类型（唯一来源是组件注册表，避免两处各写一份常量）。 */
    private static final Map<String, ComponentType<Boolean>> EFFECT_TYPES = Map.of(
        CLEAR_IMMUNITY, CreatureComponents.CLEAR_IMMUNITY,
        PULLBACK, CreatureComponents.PULLBACK,
        TELEPORT_IMMUNITY, CreatureComponents.TELEPORT_IMMUNITY,
        POTION_IMMUNITY, CreatureComponents.POTION_IMMUNITY,
        KNOCKBACK_IMMUNITY, CreatureComponents.KNOCKBACK_IMMUNITY,
        PHYSICAL_IMMUNITY, CreatureComponents.PHYSICAL_IMMUNITY,
        RIDE_IMMUNITY, CreatureComponents.RIDE_IMMUNITY
    );

    private static final ConcurrentHashMap<Class<?>, Set<String>> BASE_EFFECTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Entry> STATES = new ConcurrentHashMap<>();

    /** 单实例状态：归属玩家 + 组件补丁（效果开关以布尔组件形式存在补丁里）。 */
    private static final class Entry {
        volatile UUID owner;
        volatile ComponentPatch patch = ComponentPatch.EMPTY;
    }

    private InstanceEffectState() {}

    // ==================== 类型基础效果 ====================

    /** 注册实体类型的「基础效果」（保持某类实体默认带某些效果；自走棋招聘基础形态则不注册/用 resetToBase）。 */
    public static void registerBaseEffects(Class<? extends LivingEntity> type, String... effects) {
        Set<String> set = ConcurrentHashMap.newKeySet();
        if (effects != null) {
            for (String e : effects) set.add(e);
        }
        BASE_EFFECTS.put(type, set);
    }

    /** 沿类层级找最近的注册基础效果（未注册返回 null）。 */
    private static Set<String> baseEffects(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != LivingEntity.class; c = c.getSuperclass()) {
            Set<String> s = BASE_EFFECTS.get(c);
            if (s != null) return s;
        }
        return null;
    }

    // ==================== 效果 id ↔ 组件类型 ====================

    /** 效果 id → 组件类型；未知返回 null（容错去空白与大小写）。 */
    private static ComponentType<Boolean> effectType(String effect) {
        if (effect == null) return null;
        ComponentType<Boolean> t = EFFECT_TYPES.get(effect);
        if (t != null) return t;
        return EFFECT_TYPES.get(effect.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /** 已注册的全部效果 id（供指令补全与校验，新增效果无需改指令代码）。 */
    public static Set<String> knownEffectIds() {
        return EFFECT_TYPES.keySet();
    }

    // ==================== 判定 ====================

    /** 效果是否对当前实例生效（实例补丁优先 → 类型基础 → 默认 false）。 */
    public static boolean isEffectEnabled(LivingEntity entity, String effect) {
        ComponentType<Boolean> type = effectType(effect);
        if (entity == null || type == null) return false;
        return isEffectEnabled(entity, type);
    }

    /**
     * 组件化判定：实例补丁优先 → 生物原型（{@code yiz_creature} 的 {@code effects}）→ 类型基础 → 默认 false。
     *
     * <p>原型这一档此前没有接上：{@code CreatureProfileJson.toComponents()} 会把 {@code effects}
     * 解析成布尔组件，但没人查它 ⇒ 数据包/代码轨里写的 {@code yizmodqzk:knockback_immunity} 这类效果
     * 一直是「写了不生效」。这里接上，顺序仍满足文档约定「实例补丁 → 原型（含父链）→ 缺省」。</p>
     *
     * <p>性能：走 {@link CreatureProfileRegistry#profileEffect}（类→原型 id 查表 + 已合并组件集合查表），
     * 不做合并/分配 —— 这个判定在 {@code setDeltaMovement}/{@code knockback} 等热路径上被频繁调用。</p>
     */
    public static boolean isEffectEnabled(LivingEntity entity, ComponentType<Boolean> type) {
        if (entity == null || type == null) return false;
        Entry e = STATES.get(entity.getUUID());
        if (e != null) {
            ComponentPatch p = e.patch;
            if (p.isRemoved(type)) return false;
            Object v = p.added().get(type);
            if (v instanceof Boolean b) return b;
        }
        Boolean fromProfile = net.minecraft.client.yiz.creature.CreatureProfileRegistry.profileEffect(entity, type);
        if (fromProfile != null) return fromProfile;
        Set<String> base = baseEffects(entity.getClass());
        return base != null && base.contains(type.id().getPath());
    }

    /** 免清除快捷判定（拦清除/移除/结构摘除：mixin / agent / 基类统一入口）。 */
    public static boolean isClearImmune(LivingEntity entity) {
        return isEffectEnabled(entity, CLEAR_IMMUNITY);
    }

    /** 拉回快捷判定（被清除后自愈回填 + 快照复活：守卫线程 / 结构自愈 / 持久化统一入口）。 */
    public static boolean isPullback(LivingEntity entity) {
        return isEffectEnabled(entity, PULLBACK);
    }

    /** 存在性保护总判定 = 免清除或拉回任一开启（身份守卫/停机豁免等两属前置共用）。 */
    public static boolean isPresenceProtected(LivingEntity entity) {
        return isClearImmune(entity) || isPullback(entity);
    }

    // ==================== 归属 ====================

    /** 实体归属玩家 UUID；无主返回 null。 */
    public static UUID getOwner(LivingEntity entity) {
        if (entity == null) return null;
        Entry e = STATES.get(entity.getUUID());
        return e == null ? null : e.owner;
    }

    /** 设置实体归属玩家。 */
    public static void setOwner(LivingEntity entity, UUID ownerUuid) {
        if (entity == null) return;
        entry(entity).owner = ownerUuid;
    }

    // ==================== 效果开关 ====================

    /**
     * 对实例开关某效果（显式覆盖类型基础）。归属校验：操作者 == 归属玩家，或本模组调用栈（指令/服务器）。
     *
     * @return 是否允许修改
     */
    public static boolean setEffect(LivingEntity entity, UUID operatorUuid, String effect, boolean on) {
        ComponentType<Boolean> type = effectType(effect);
        if (entity == null || type == null) return false;
        return setEffect(entity, type, on, operatorUuid);
    }

    /** 组件化开关：写入实例补丁。 */
    public static boolean setEffect(LivingEntity entity, ComponentType<Boolean> type, boolean on, UUID operatorUuid) {
        if (entity == null || type == null) return false;
        Entry e = entry(entity);
        if (!canModify(e, operatorUuid)) return false;
        e.patch = on ? e.patch.with(type, Boolean.TRUE) : e.patch.without(type);
        return true;
    }

    // ==================== 通用组件读写（供属性/战斗组件复用） ====================

    /** 实例的组件补丁（无则 EMPTY）。 */
    public static ComponentPatch patchOf(LivingEntity entity) {
        Entry e = entity == null ? null : STATES.get(entity.getUUID());
        return e == null ? ComponentPatch.EMPTY : e.patch;
    }

    /** 通用组件读取：实例补丁优先，缺失回退组件缺省值。 */
    @SuppressWarnings("unchecked")
    public static <T> T get(LivingEntity entity, ComponentType<T> type) {
        if (entity == null || type == null) return null;
        Entry e = STATES.get(entity.getUUID());
        if (e != null && !e.patch.isRemoved(type)) {
            Object v = e.patch.added().get(type);
            if (v != null) return (T) v;
        }
        return type.defaultValue();
    }

    /** 通用组件写入（受归属校验）。 */
    public static <T> boolean set(LivingEntity entity, ComponentType<T> type, T value, UUID operatorUuid) {
        if (entity == null || type == null) return false;
        Entry e = entry(entity);
        if (!canModify(e, operatorUuid)) return false;
        e.patch = e.patch.with(type, value);
        return true;
    }

    /** 合并一批组件覆盖（数据包 / 棋子维度批量应用，不做归属校验，由调用方保证来源可信）。 */
    public static void mergePatch(LivingEntity entity, ComponentPatch patch) {
        if (entity == null || patch == null || patch.isEmpty()) return;
        Entry e = entry(entity);
        e.patch = e.patch.merge(patch);
    }

    /** 清除实例的显式覆盖，恢复「类型基础形态」（保留归属）。 */
    public static void resetToBase(LivingEntity entity) {
        if (entity == null) return;
        Entry e = STATES.get(entity.getUUID());
        if (e != null) e.patch = ComponentPatch.EMPTY;
    }

    /** 实体移除/真实死亡时清理状态（防 UUID 复用残留）。 */
    public static void remove(UUID uuid) {
        if (uuid != null) STATES.remove(uuid);
    }

    /** 免清除关闭判定：非免清除实例（mixin/基类用「不拦截清除」语义）。 */
    public static boolean isNotClearImmune(LivingEntity entity) {
        return !isClearImmune(entity);
    }

    /** 拉回关闭判定：非拉回实例。 */
    public static boolean isNotPullback(LivingEntity entity) {
        return !isPullback(entity);
    }

    private static boolean canModify(Entry e, UUID operatorUuid) {
        if (e.owner == null) return true;  // 无主实体：任意玩家可增强（招聘）
        if (operatorUuid != null && operatorUuid.equals(e.owner)) return true;
        return isServerTrusted();
    }

    private static boolean isServerTrusted() {
        try {
            return net.minecraft.client.yiz.tool.attribute.EntityAttributeGate.isCallerTrusted();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Entry entry(LivingEntity entity) {
        return STATES.computeIfAbsent(entity.getUUID(), u -> new Entry());
    }

    // ==================== 持久化 ====================

    /** 序列化实例效果态（owner + 显式覆盖）到 NBT（由实体 addAdditionalSaveData 调用）。 */
    public static void writeState(LivingEntity entity, CompoundTag tag) {
        if (entity == null || tag == null) return;
        Entry e = STATES.get(entity.getUUID());
        if (e == null) return;
        ComponentPatch p = e.patch;
        if (e.owner == null && p.isEmpty()) return;
        Set<String> enabled = new LinkedHashSet<>();
        Set<String> disabled = new LinkedHashSet<>();
        p.added().forEach((type, value) -> {
            if (Boolean.TRUE.equals(value) && isEffectType(type)) enabled.add(type.id().getPath());
        });
        for (ComponentType<?> type : p.removed()) {
            if (isEffectType(type)) disabled.add(type.id().getPath());
        }
        if (e.owner == null && enabled.isEmpty() && disabled.isEmpty()) return;
        CompoundTag state = new CompoundTag();
        if (e.owner != null) state.putUUID("owner", e.owner);
        if (!enabled.isEmpty()) state.put("enabled", writeList(enabled));
        if (!disabled.isEmpty()) state.put("disabled", writeList(disabled));
        tag.put("yiz_effects", state);
    }

    /** 从 NBT 恢复实例效果态（由实体 readAdditionalSaveData 调用）。 */
    public static void readState(LivingEntity entity, CompoundTag tag) {
        if (entity == null || tag == null) return;
        if (!tag.contains("yiz_effects", Tag.TAG_COMPOUND)) return;
        CompoundTag state = tag.getCompound("yiz_effects");
        Entry e = entry(entity);
        if (state.contains("owner")) e.owner = state.getUUID("owner");
        Set<String> enabled = new LinkedHashSet<>();
        Set<String> disabled = new LinkedHashSet<>();
        if (state.contains("enabled")) readList(state.getList("enabled", Tag.TAG_STRING), enabled);
        if (state.contains("disabled")) readList(state.getList("disabled", Tag.TAG_STRING), disabled);
        migrateLegacyRemoveImmunity(enabled, disabled);
        ComponentPatch p = e.patch;
        for (String id : enabled) {
            ComponentType<Boolean> type = effectType(id);
            if (type != null) p = p.with(type, Boolean.TRUE);
        }
        for (String id : disabled) {
            ComponentType<Boolean> type = effectType(id);
            if (type != null) p = p.without(type);
        }
        e.patch = p;
    }

    /** 该组件类型是否属于效果类（用于决定写不写进 yiz_effects 存档）。 */
    private static boolean isEffectType(ComponentType<?> type) {
        return type != null && EFFECT_TYPES.containsValue(type);
    }

    /** 一次性迁移：老档 enabled/disabled 含 legacy {@code remove_immunity}（旧单开关总控免移除）
     *  → 展开为 {@link #CLEAR_IMMUNITY} + {@link #PULLBACK}（运行期只认新双 key）。 */
    private static void migrateLegacyRemoveImmunity(Set<String> enabled, Set<String> disabled) {
        String legacy = "remove_immunity";
        if (enabled.remove(legacy)) {
            enabled.add(CLEAR_IMMUNITY);
            enabled.add(PULLBACK);
        }
        if (disabled.remove(legacy)) {
            disabled.add(CLEAR_IMMUNITY);
            disabled.add(PULLBACK);
        }
    }

    private static ListTag writeList(Set<String> set) {
        ListTag list = new ListTag();
        for (String s : set) list.add(StringTag.valueOf(s));
        return list;
    }

    private static void readList(ListTag list, Set<String> target) {
        for (Tag t : list) {
            if (t instanceof StringTag st) target.add(st.getAsString());
        }
    }
}
