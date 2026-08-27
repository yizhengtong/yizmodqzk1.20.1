package net.minecraft.client.yiz.tool.effect;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每实例每玩家效果隔离注册表（自走棋棋子效果模型基础）。
 *
 * <p>实体效果（免移除/免传送/免药水/免物理/免骑乘）不再是基类硬编码/全局静态开关，
 * 而是<b>每实体实例</b>一份状态：归属玩家 + 显式开启/关闭覆盖。判定顺序：</p>
 * <ol>
 *   <li>实例显式覆盖（enabled/disabled Set）优先；</li>
 *   <li>该实体类型的「基础效果」（{@link #registerBaseEffects}）；</li>
 *   <li>默认 false（基础形态，需玩家增强才开启）。</li>
 * </ol>
 *
 * <p>归属校验：修改效果需操作者 == 实体归属玩家，或经
 * {@code EntityAttributeGate.isCallerTrusted()} 的本模组调用栈（指令/服务器）。
 * 玩家之间天然隔离：A 对 A 名下实例的增强不作用于 B 的实例。</p>
 */
public final class InstanceEffectState {

    /** 免移除（整体放弃不死：OFF 时守卫线程/拉回/持久化复活全放行）。 */
    public static final String REMOVE_IMMUNITY = "remove_immunity";
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

    private static final ConcurrentHashMap<Class<?>, Set<String>> BASE_EFFECTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Entry> STATES = new ConcurrentHashMap<>();

    private static final class Entry {
        volatile UUID owner;
        final Set<String> enabled = ConcurrentHashMap.newKeySet();
        final Set<String> disabled = ConcurrentHashMap.newKeySet();
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

    // ==================== 判定 ====================

    /** 效果是否对当前实例生效（实例显式覆盖优先 → 类型基础 → 默认 false）。 */
    public static boolean isEffectEnabled(LivingEntity entity, String effect) {
        if (entity == null || effect == null) return false;
        Entry e = STATES.get(entity.getUUID());
        if (e != null) {
            if (e.enabled.contains(effect)) return true;
            if (e.disabled.contains(effect)) return false;
        }
        Set<String> base = baseEffects(entity.getClass());
        return base != null && base.contains(effect);
    }

    /** 免移除快捷判定（mixin / agent / 基类统一入口）。 */
    public static boolean isRemoveProtected(LivingEntity entity) {
        return isEffectEnabled(entity, REMOVE_IMMUNITY);
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
        if (entity == null || effect == null) return false;
        Entry e = entry(entity);
        if (!canModify(e, operatorUuid)) return false;
        if (on) {
            e.enabled.add(effect);
            e.disabled.remove(effect);
        } else {
            e.enabled.remove(effect);
            e.disabled.add(effect);
        }
        return true;
    }

    /** 清除实例的显式覆盖，恢复「类型基础形态」（保留归属）。 */
    public static void resetToBase(LivingEntity entity) {
        if (entity == null) return;
        Entry e = STATES.get(entity.getUUID());
        if (e != null) {
            e.enabled.clear();
            e.disabled.clear();
        }
    }

    /** 实体移除/真实死亡时清理状态（防 UUID 复用残留）。 */
    public static void remove(UUID uuid) {
        if (uuid != null) STATES.remove(uuid);
    }

    /** 免移除关闭判定：非受保护实例（mixin/基类用「不拦截」语义）。 */
    public static boolean isNotRemoveProtected(LivingEntity entity) {
        return !isRemoveProtected(entity);
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
        if (e == null || (e.owner == null && e.enabled.isEmpty() && e.disabled.isEmpty())) return;
        CompoundTag state = new CompoundTag();
        if (e.owner != null) state.putUUID("owner", e.owner);
        if (!e.enabled.isEmpty()) state.put("enabled", writeList(e.enabled));
        if (!e.disabled.isEmpty()) state.put("disabled", writeList(e.disabled));
        tag.put("yiz_effects", state);
    }

    /** 从 NBT 恢复实例效果态（由实体 readAdditionalSaveData 调用）。 */
    public static void readState(LivingEntity entity, CompoundTag tag) {
        if (entity == null || tag == null) return;
        if (!tag.contains("yiz_effects", Tag.TAG_COMPOUND)) return;
        CompoundTag state = tag.getCompound("yiz_effects");
        Entry e = entry(entity);
        if (state.contains("owner")) e.owner = state.getUUID("owner");
        if (state.contains("enabled")) readList(state.getList("enabled", Tag.TAG_STRING), e.enabled);
        if (state.contains("disabled")) readList(state.getList("disabled", Tag.TAG_STRING), e.disabled);
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
