package net.minecraft.client.yiz.tool;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
                Method removeMethod = Entity.class.getDeclaredMethod("remove", Entity.RemovalReason.class);
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
