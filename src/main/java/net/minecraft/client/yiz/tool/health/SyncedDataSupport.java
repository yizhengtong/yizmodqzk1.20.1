package net.minecraft.client.yiz.tool.health;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * {@code SynchedEntityData} 通道自愈支持（独立 holder 类）。
 *
 * <p><b>为什么不写在 mixin 里：</b>mixin 的静态字段初始化会被合并进目标类的 {@code <clinit>}，
 * 生产 SRG 环境下引用原版字段会 NoSuchFieldError（见记忆 mixin-unique-static-clinit-crash）。
 * 本类在 mixin 包之外，普通类加载。</p>
 *
 * <h3>要解决的问题</h3>
 * <p>1.20.1 的通道 id 是「类池 + 类加载顺序」决定的<b>全局可变状态</b>。生产实测：某第三方
 * accessor 的 id 为 0（与 {@code Entity.DATA_SHARED_FLAGS_ID} 同槽），且它被 define 进了实体
 * 数据表 → 原版 {@code Entity.<init>} 首次 define 就抛
 * {@code Duplicate id value for 0!} → 实体构造失败（玩家登录被踢「无效的玩家数据」、
 * 拾取粒子建假 ItemEntity 时崩客户端）。</p>
 *
 * <p>本类的策略：<b>原版通道优先</b>。冲突时若本次定义的原版（{@code net.minecraft.} 声明）通道，
 * 就驱逐占用槽位的外来条目让原版定义成功；否则保留先到的条目、丢弃这次定义（不抛异常）。
 * 被丢弃的通道读到的是类型安全默认值（见 {@link #defaultFor}），不再让游戏崩。</p>
 */
public final class SyncedDataSupport {

    private SyncedDataSupport() {}

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;

    /** 快照表字段（{@code SynchedEntityData.itemsById}），按类型定位一次后缓存。 */
    private static volatile Field itemsByIdField;
    /** 读写锁字段（{@code SynchedEntityData.lock}）。 */
    private static volatile Field lockField;
    /** 实体字段（{@code SynchedEntityData.entity}）。 */
    private static volatile Field entityField;
    /** DataItem 持有的 accessor 字段。 */
    private static volatile Field dataItemAccessorField;

    /** 实体类 → （通道 → 声明该通道的类）。用于判断"这次 define 是不是本实体继承链自己的通道"。 */
    private static final Map<Class<?>, Map<EntityDataAccessor<?>, Class<?>>>
        DECLARED = new ConcurrentHashMap<>();

    /** 已告警过的冲突键，防刷屏。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    // ==================== 反射入口 ====================

    @SuppressWarnings("unchecked")
    public static Int2ObjectMap<SynchedEntityData.DataItem<?>> itemsById(SynchedEntityData data) {
        try {
            Field f = itemsByIdField;
            if (f == null) {
                f = findField(SynchedEntityData.class, Int2ObjectMap.class);
                if (f == null) return null;
                itemsByIdField = f;
            }
            return (Int2ObjectMap<SynchedEntityData.DataItem<?>>) f.get(data);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在写锁保护下补一个 DataItem（读守卫遇到"通道从未定义"时用）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void putItem(SynchedEntityData data,
                               Int2ObjectMap<SynchedEntityData.DataItem<?>> map,
                               EntityDataAccessor<?> key, Object value) {
        java.util.concurrent.locks.Lock lock = writeLock(data);
        try {
            if (lock != null) lock.lock();
            map.put(key.getId(), new SynchedEntityData.DataItem(key, value));
        } catch (Throwable ignored) {
        } finally {
            if (lock != null) {
                try { lock.unlock(); } catch (Throwable ignored) {}
            }
        }
    }

    private static java.util.concurrent.locks.Lock writeLock(SynchedEntityData data) {
        try {
            Field f = lockField;
            if (f == null) {
                f = findField(SynchedEntityData.class, ReadWriteLock.class);
                if (f == null) return null;
                lockField = f;
            }
            Object o = f.get(data);
            return o instanceof ReadWriteLock rwl ? rwl.writeLock() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 DataItem 持有的通道（反射读，避免依赖 {@code getAccessor()} 的开发名）。 */
    public static EntityDataAccessor<?> accessorOf(SynchedEntityData.DataItem<?> item) {
        if (item == null) return null;
        try {
            Field f = dataItemAccessorField;
            if (f == null) {
                f = findField(SynchedEntityData.DataItem.class, EntityDataAccessor.class);
                if (f == null) return null;
                dataItemAccessorField = f;
            }
            Object o = f.get(item);
            return o instanceof EntityDataAccessor<?> a ? a : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Entity entityOf(SynchedEntityData data) {
        try {
            Field f = entityField;
            if (f == null) {
                f = findField(SynchedEntityData.class, Entity.class);
                if (f == null) return null;
                entityField = f;
            }
            Object o = f.get(data);
            return o instanceof Entity e ? e : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> owner, Class<?> type) {
        for (Field f : owner.getDeclaredFields()) {
            if (type.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    // ==================== 冲突决策 ====================

    /**
     * 通道 id 已被占用时决定谁留下。
     *
     * @return {@code true} = 保留已有条目，调用方应取消这次 define；{@code false} = 驱逐已有条目，
     *         让这次 define 正常写入。
     */
    public static boolean keepExisting(SynchedEntityData data, EntityDataAccessor<?> incoming,
                                       EntityDataAccessor<?> existing) {
        try {
            Entity entity = entityOf(data);
            Class<?> entityClass = entity == null ? null : entity.getClass();
            String incomingOwner = declaringOwner(entityClass, incoming);
            String existingOwner = declaringOwner(entityClass, existing);
            boolean incomingVanilla = isVanilla(incomingOwner);
            boolean existingVanilla = isVanilla(existingOwner);

            // 原版优先：原版通道要么拿回槽位，要么保住已占的槽位。
            if (incomingVanilla && !existingVanilla) {
                log("原版通道被外来通道占用 → 驱逐外来条目，恢复原版定义", entityClass, incoming,
                    incomingOwner, existing, existingOwner, true);
                return false;
            }
            if (incomingVanilla && existingVanilla) {
                log("两个原版通道撞同一个 id（类池被外部改动）→ 保留先到的", entityClass, incoming,
                    incomingOwner, existing, existingOwner, true);
                return true;
            }
            // 其余情况：保留先到的条目（无论先到的是原版还是第三方），丢弃这次定义，不抛异常。
            log(existingVanilla ? "第三方通道试图占用原版槽位 → 丢弃这次定义"
                                : "两个第三方通道撞同一个 id → 保留先到的",
                entityClass, incoming, incomingOwner, existing, existingOwner, false);
            return true;
        } catch (Throwable t) {
            return true;   // 决策失败时保守：保留已有条目
        }
    }

    private static boolean isVanilla(String owner) {
        return owner != null && owner.startsWith("net.minecraft.");
    }

    /** 在本实体继承链上查该通道的声明类名；不在链上返回 {@code null}（即外来通道）。 */
    private static String declaringOwner(Class<?> entityClass, EntityDataAccessor<?> accessor) {
        if (entityClass == null || accessor == null) return null;
        try {
            Class<?> owner = DECLARED
                .computeIfAbsent(entityClass, SyncedDataSupport::scanDeclared)
                .get(accessor);
            return owner == null ? null : owner.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 扫实体继承链上声明的所有静态通道字段（只扫到 Entity，量很小且按类缓存）。 */
    private static Map<EntityDataAccessor<?>, Class<?>> scanDeclared(Class<?> entityClass) {
        Map<EntityDataAccessor<?>, Class<?>> out = new java.util.IdentityHashMap<>();
        try {
            for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(null);
                        if (v instanceof EntityDataAccessor<?> a) out.put(a, c);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void log(String what, Class<?> entityClass, EntityDataAccessor<?> incoming,
                            String incomingOwner, EntityDataAccessor<?> existing,
                            String existingOwner, boolean evict) {
        String key = (entityClass == null ? "?" : entityClass.getName()) + "#" + incoming.getId();
        if (!LOGGED.add(key)) return;
        LOGGER.error("[SynchedEntityData] 通道 id 冲突（id={}，实体={}）：{}；"
                + "本次 accessor={}（{}，序列化器={}），占用者={}（{}）→ {}",
            incoming.getId(), entityClass == null ? "?" : entityClass.getName(), what,
            incoming, incomingOwner == null ? "外来" : incomingOwner,
            incoming.getSerializer().getClass().getSimpleName(),
            existing, existingOwner == null ? "外来" : existingOwner,
            evict ? "驱逐占用者、保留本次定义" : "保留占用者、丢弃本次定义");
        // 调用栈点名「谁在给这个实体加通道」——定位第三方模组用
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder("[SynchedEntityData] 冲突调用栈:");
            for (int i = 1; i < st.length && i <= 12; i++) {
                sb.append("\n    at ").append(st[i]);
            }
            LOGGER.error(sb.toString());
        } catch (Throwable ignored) {}
    }

    // ==================== 类型安全默认值 ====================

    /**
     * 值类型与序列化器不匹配时的兜底值；返回 {@code null} 表示"无需修复"（类型正常或未覆盖的序列化器）。
     */
    public static Object defaultFor(EntityDataSerializer<?> ser, Object v) {
        // 基础类型
        if (ser == EntityDataSerializers.INT) return v instanceof Integer ? null : (Object) 0;
        if (ser == EntityDataSerializers.LONG) return v instanceof Long ? null : (Object) 0L;
        if (ser == EntityDataSerializers.FLOAT) return v instanceof Float ? null : (Object) 0.0F;
        if (ser == EntityDataSerializers.BYTE) return v instanceof Byte ? null : (Object) (byte) 0;
        if (ser == EntityDataSerializers.BOOLEAN) return v instanceof Boolean ? null : (Object) false;
        if (ser == EntityDataSerializers.STRING) return v instanceof String ? null : (Object) "";
        // 对象类型（读取端最后一道防线）
        if (ser == EntityDataSerializers.COMPONENT) {
            return v instanceof net.minecraft.network.chat.Component ? null
                    : net.minecraft.network.chat.Component.empty();
        }
        if (ser == EntityDataSerializers.OPTIONAL_COMPONENT || ser == EntityDataSerializers.OPTIONAL_BLOCK_STATE
                || ser == EntityDataSerializers.OPTIONAL_BLOCK_POS || ser == EntityDataSerializers.OPTIONAL_UUID
                || ser == EntityDataSerializers.OPTIONAL_GLOBAL_POS) {
            return v instanceof java.util.Optional ? null : java.util.Optional.empty();
        }
        if (ser == EntityDataSerializers.ITEM_STACK) {
            return v instanceof net.minecraft.world.item.ItemStack ? null
                    : net.minecraft.world.item.ItemStack.EMPTY;
        }
        if (ser == EntityDataSerializers.BLOCK_STATE) {
            return v instanceof net.minecraft.world.level.block.state.BlockState ? null
                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        if (ser == EntityDataSerializers.BLOCK_POS) {
            return v instanceof net.minecraft.core.BlockPos ? null : net.minecraft.core.BlockPos.ZERO;
        }
        if (ser == EntityDataSerializers.DIRECTION) {
            return v instanceof net.minecraft.core.Direction ? null : net.minecraft.core.Direction.NORTH;
        }
        if (ser == EntityDataSerializers.COMPOUND_TAG) {
            return v instanceof net.minecraft.nbt.CompoundTag ? null : new net.minecraft.nbt.CompoundTag();
        }
        return null;
    }
}
