package net.minecraft.client.yiz.tool;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 实体强制移除工具（1.20.1 移植版）— 多条绕过路径按优先级尝试。
 *
 * <p>绕过路径：MethodHandle Entity.remove()（IMPL_LOOKUP unreflectSpecial 跳过子类 override）
 * → Unsafe 设 removed + ServerLevel 反注册 → discard() 兜底。</p>
 */
@SuppressWarnings("removal")
public final class EntityRemovalUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger("EntityRemoval");
    private static final Unsafe U;
    private static final MethodHandle ENTITY_REMOVE_BASE;
    private static final long REMOVED_OFFSET;
    private static final boolean AVAILABLE;

    static {
        Unsafe u = null;
        MethodHandle handle = null;
        long removedOff = -1;
        boolean ok = false;

        try {
            u = getUnsafe();

            try {
                // 双名匹配（official + SRG）：生产环境 remove 方法名是 m_142687_
                Method removeMethod = null;
                try {
                    removeMethod = Entity.class.getDeclaredMethod("remove", Entity.RemovalReason.class);
                } catch (NoSuchMethodException e) {
                    removeMethod = Entity.class.getDeclaredMethod("m_142687_", Entity.RemovalReason.class);
                }
                removeMethod.setAccessible(true);
                Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
                long implLookupOffset = u.staticFieldOffset(implLookupField);
                MethodHandles.Lookup trusted = (MethodHandles.Lookup)
                        u.getObject(u.staticFieldBase(implLookupField), implLookupOffset);
                handle = trusted.unreflectSpecial(removeMethod, Entity.class);
            } catch (Exception e) {
                LOGGER.warn("[EntityRemovalUtil] MethodHandle unavailable: {}", e.getMessage());
            }

            for (Field f : Entity.class.getDeclaredFields()) {
                if (f.getType() == boolean.class
                        && (f.getName().contains("remov") || f.getName().contains("Remov"))) {
                    removedOff = u.objectFieldOffset(f);
                    break;
                }
            }

            ok = u != null;
        } catch (Exception e) {
            LOGGER.error("[EntityRemovalUtil] Init failed: {}", e.getMessage());
        }

        U = u;
        ENTITY_REMOVE_BASE = handle;
        REMOVED_OFFSET = removedOff;
        AVAILABLE = ok;
    }

    private EntityRemovalUtil() {}

    /** 强制移除实体。成功返回 true。 */
    public static boolean forceRemove(Entity entity) {
        if (entity == null || entity.isRemoved()) return false;

        if (ENTITY_REMOVE_BASE != null) {
            try {
                ENTITY_REMOVE_BASE.invoke(entity, Entity.RemovalReason.DISCARDED);
                return true;
            } catch (Throwable e) {
                LOGGER.trace("[EntityRemovalUtil] MethodHandle: {}", e.getMessage());
            }
        }

        if (U != null && REMOVED_OFFSET >= 0) {
            try {
                if (entity.level() instanceof ServerLevel sl) {
                    sl.getChunkSource().removeEntity(entity);
                }
                U.putBoolean(entity, REMOVED_OFFSET, true);
                return true;
            } catch (Exception e) {
                LOGGER.trace("[EntityRemovalUtil] Unsafe: {}", e.getMessage());
            }
        }

        try {
            entity.discard();
            return true;
        } catch (Exception e) {
            LOGGER.error("[EntityRemovalUtil] All paths failed for {}", entity);
            return false;
        }
    }

    /**
     * 深层结构直删：从世界内部数据结构直接摘除实体——
     * EntitySection（空间索引）+ EntityLookup.visibleEntityStorage（id/uuid 索引）+ entityTickList
     * + chunkSource.removeEntity + 主动发移除包 + remove(KILLED)。用于「免改血/免删除」实体的致死兜底，
     * 比 {@link #forceRemove} 更深一层。
     *
     * <p>反射按字段名访问（ServerLevel.entityManager → sectionStorage/visibleEntityStorage/entityTickList），
     * 字段找不到静默跳过（1.20.1 若 entityTickList 不存在则跳过），不崩。泛型参数用 EntityAccess.class
     * （类型擦除后 remove(EntityAccess)）。</p>
     */
    public static boolean forceRemoveDeep(ServerLevel level, Entity entity) {
        if (entity == null || level == null) return false;
        boolean any = false;

        try { entity.remove(Entity.RemovalReason.KILLED); any = true; } catch (Throwable ignored) {}
        try { entity.setRemoved(Entity.RemovalReason.KILLED); } catch (Throwable ignored) {}
        try {
            entity.stopRiding();
            for (Entity passenger : entity.getPassengers()) passenger.stopRiding();
        } catch (Throwable ignored) {}
        try { entity.onRemovedFromWorld(); } catch (Throwable ignored) {}
        if (levelCallbackOnRemove(entity, Entity.RemovalReason.KILLED)) any = true;

        // 1. ServerLevel.entityManager → sectionStorage / visibleEntityStorage / entityTickList
        try {
            Object mgr = readField(level, "entityManager");
            if (mgr != null) {
                // 1a. EntitySection.remove（空间索引，getSection(sectionKey)）
                Object sectionStorage = readField(mgr, "sectionStorage");
                if (sectionStorage != null) {
                    try {
                        long sectionKey = SectionPos.asLong(entity.blockPosition());
                        // 双名匹配（official + SRG）：生产环境 getSection 方法名是 m_156895_
                        Method getSection = null;
                        try {
                            getSection = sectionStorage.getClass().getMethod("getSection", long.class);
                        } catch (NoSuchMethodException e) {
                            getSection = sectionStorage.getClass().getMethod("m_156895_", long.class);
                        }
                        Object section = getSection.invoke(sectionStorage, sectionKey);
                        if (section != null) {
                            section.getClass().getMethod("remove", EntityAccess.class).invoke(section, entity);
                            any = true;
                        }
                    } catch (Throwable ignored) {}
                }
                // 1b. EntityLookup.remove（id/uuid 索引）
                Object visible = readField(mgr, "visibleEntityStorage");
                if (visible != null) {
                    try {
                        visible.getClass().getMethod("remove", EntityAccess.class).invoke(visible, entity);
                        any = true;
                    } catch (Throwable ignored) {}
                }
                // 1c. entityTickList.remove（tick 列表；1.20.1 若字段不存在则跳过）
                Object tickList = readField(mgr, "entityTickList");
                if (tickList != null) {
                    try {
                        tickList.getClass().getMethod("remove", Entity.class).invoke(tickList, entity);
                        any = true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // 2. chunkSource.removeEntity（世界存储汇聚点）
        try {
            level.getChunkSource().removeEntity(entity);
            any = true;
        } catch (Throwable ignored) {}

        // 3. 主动发 ClientboundRemoveEntitiesPacket（即使服务器内部已摘，也要让客户端知道实体没了）
        try {
            ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(entity.getId());
            for (ServerPlayer player : level.players()) {
                player.connection.send(packet);
            }
        } catch (Throwable ignored) {}

        try { entity.invalidateCaps(); } catch (Throwable ignored) {}

        // 5. 兜底走三层 forceRemove
        try { forceRemove(entity); } catch (Throwable ignored) {}

        return any;
    }

    /** 按字段名反射读实例字段（找不到返回 null）。 */
    private static Object readField(Object owner, String name) {
        if (owner == null) return null;
        try {
            java.lang.reflect.Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean levelCallbackOnRemove(Entity entity, Entity.RemovalReason reason) {
        try {
            java.lang.reflect.Field lcField = null;
            for (java.lang.reflect.Field f : Entity.class.getDeclaredFields()) {
                if (net.minecraft.world.level.entity.EntityInLevelCallback.class.isAssignableFrom(f.getType())) {
                    lcField = f;
                    break;
                }
            }
            if (lcField == null) return false;
            lcField.setAccessible(true);
            Object lc = lcField.get(entity);
            if (lc == null) return false;
            // 按签名找 onRemove(RemovalReason)（不猜 SRG 名）：单参数 RemovalReason、返回 void
            for (java.lang.reflect.Method m : lc.getClass().getMethods()) {
                if (m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Entity.RemovalReason.class
                        && m.getReturnType() == void.class) {
                    m.invoke(lc, reason);
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Unsafe getUnsafe() {
        try {
            Constructor<Unsafe> c = Unsafe.class.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Exception e1) {
            try {
                Field f = Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                return (Unsafe) f.get(null);
            } catch (Exception e2) {
                throw new RuntimeException("Cannot get Unsafe", e2);
            }
        }
    }
}
