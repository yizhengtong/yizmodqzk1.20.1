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

    /**
     * 启动自检时记下的「声明类.字段名 → id@对象身份」快照。
     *
     * <p>用来抓 id 撞车的真凶：生产实测同一个类在<b>启动时通道 id 完全正常</b>（0..7、类池=7），
     * 打到某场战斗中途却出现「两个通道同 id」——说明字段里装的 accessor <b>在会话中途被换掉/改掉了</b>。
     * 冲突时把「启动时的 id/对象」与「现在的 id/对象」并排打出来，就能直接看出是哪条字段漂移、
     * 是换了对象还是改了 id。</p>
     */
    private static final Map<String, String> CHANNEL_SNAPSHOT = new ConcurrentHashMap<>();

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
        // ⚠️ 下游模组的类也声明在 net.minecraft.client.yiz.xian.*（影子包名），只按 net.minecraft.
        // 前缀判断会把本模组自己的通道误判成原版 → 冲突时走「原版优先」去驱逐真正的原版通道，
        // 把 vanilla 的槽位让给自家通道。必须先排除本模组自己的类。
        return owner != null && owner.startsWith("net.minecraft.") && !HealthSelfFilter.isOwnClass(owner);
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
        // 字段名是关键证据：两个 accessor 都「声明在 Entity」时，只有字段名能说明到底是哪两条
        // 通道撞了同一个 id（生产 id 0 撞车就是靠这个才能确定 DATA_POSE 被谁占了槽）。
        LOGGER.error("[SynchedEntityData] 通道 id 冲突（id={}，实体={}）：{}；"
                + "本次 accessor={}[{}]（{}，序列化器={}），占用者={}[{}]（{}）→ {}；类池[Entity]={}",
            incoming.getId(), entityClass == null ? "?" : entityClass.getName(), what,
            incoming, describeAccessor(entityClass, incoming),
            incomingOwner == null ? "外来" : incomingOwner,
            incoming.getSerializer().getClass().getSimpleName(),
            existing, describeAccessor(entityClass, existing),
            existingOwner == null ? "外来" : existingOwner,
            evict ? "驱逐占用者、保留本次定义" : "保留占用者、丢弃本次定义",
            entityPoolId());
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

    /** accessor 在实体继承链上的「声明类.字段名」；定位不到返回 {@code "?"}（仅诊断用）。 */
    private static String fieldNameOf(Class<?> entityClass, EntityDataAccessor<?> accessor) {
        if (entityClass == null || accessor == null) return "?";
        try {
            for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        if (f.get(null) == accessor) return c.getSimpleName() + "." + f.getName();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    /**
     * 诊断：把一个 accessor 描述成「字段名(id@身份)」，并与启动快照对比，漂移时并排打出启动值。
     *
     * <p>这是定位「谁在会话中途换掉了通道」的关键证据：只看当前 id 无法区分
     * 「字段被换成别的 accessor」和「同一个 accessor 的 id 被改」——把启动时的
     * {@code id@identityHashCode} 一起打出来就一目了然。</p>
     */
    private static String describeAccessor(Class<?> entityClass, EntityDataAccessor<?> accessor) {
        if (accessor == null) return "?";
        String field = fieldNameOf(entityClass, accessor);
        String now = accessor.getId() + "@" + Integer.toHexString(System.identityHashCode(accessor));
        String before = CHANNEL_SNAPSHOT.get(field);
        if (before == null) return field + "(id=" + accessor.getId() + ")";
        if (before.equals(now)) return field + "(id=" + accessor.getId() + "，与启动一致)";
        return field + " ⚠漂移: 启动时=" + before + " → 现在=" + now;
    }

    // ==================== 启动自检：Entity 通道 id ====================

    /**
     * 自检 vanilla {@code Entity} 的通道 id 是否仍然「一个通道一个 id」。
     *
     * <p>1.20.1 的 id 来自 {@code SynchedEntityData} 的<b>全局可变类池</b>（{@code ENTITY_ID_POOL}）。
     * 生产实测出现过：{@code Entity} 的两个通道拿到同一个 id 0（例如 {@code DATA_POSE} 与
     * {@code DATA_SHARED_FLAGS_ID} 同槽）→ 读 {@code getPose()} 读出 {@code Byte} →
     * {@code ClassCastException} 崩服务端 tick + 客户端渲染。这里在启动时把 id 打出来并点名重复项，
     * 便于下次直接从日志确认类池是否被外部改动过（只读，不改任何状态）。</p>
     */
    public static void auditEntityChannelIds() {
        try {
            java.util.List<Field> channels = new java.util.ArrayList<>();
            for (Field f : Entity.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                channels.add(f);
            }
            if (channels.isEmpty()) return;
            // 字段声明顺序 == Entity.<clinit> 里 defineId 的调用顺序 → 正常时 id 应等于序号
            StringBuilder ids = new StringBuilder();
            java.util.Map<Integer, String> seen = new java.util.LinkedHashMap<>();
            java.util.List<String> dup = new java.util.ArrayList<>();
            int index = 0;
            for (Field f : channels) {
                f.setAccessible(true);
                Object v = f.get(null);
                int id = v instanceof EntityDataAccessor<?> a ? a.getId() : -1;
                if (ids.length() > 0) ids.append(' ');
                ids.append(f.getName()).append('=').append(id);
                // 记快照：冲突时用来判断「字段被换掉」还是「同一对象的 id 被改」
                if (v != null) {
                    CHANNEL_SNAPSHOT.put("Entity." + f.getName(),
                            id + "@" + Integer.toHexString(System.identityHashCode(v)));
                }
                String prev = seen.put(id, f.getName());
                if (prev != null) dup.add(f.getName() + " 与 " + prev + " 同为 id " + id);
                index++;
            }
            LOGGER.info("[SynchedEntityData] Entity 通道 id 自检（正常=序号）: {}；实际 {} 条", ids, index);
            int pooled = entityPoolId();
            LOGGER.info("[SynchedEntityData] 类池 ENTITY_ID_POOL[Entity]={}（正常={}）",
                pooled < 0 ? "读取失败" : pooled, index - 1);
            if (!dup.isEmpty()) {
                LOGGER.error("[SynchedEntityData] ⚠ Entity 通道 id 重复：{} —— 类池已被外部改动，"
                        + "这些槽位互相覆盖（读出来会是别的通道的类型，轻则数值错乱、重则 "
                        + "ClassCastException 崩 tick/渲染）。读守卫已按序列化器兜底默认值。", dup);
            }
        } catch (Throwable t) {
            LOGGER.warn("[SynchedEntityData] Entity 通道 id 自检跳过: {}", t.toString());
        }
    }

    /** 读 {@code SynchedEntityData} 里类池对 {@code Entity.class} 的登记值；读不到返回 -1。 */
    private static int entityPoolId() {
        try {
            Field poolField = findField(SynchedEntityData.class,
                it.unimi.dsi.fastutil.objects.Object2IntMap.class);
            if (poolField == null) return -1;
            Object pool = poolField.get(null);
            if (pool instanceof it.unimi.dsi.fastutil.objects.Object2IntMap<?> m) {
                return m.containsKey(Entity.class) ? m.getInt(Entity.class) : -1;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /** 已告警过的漂移签名，防刷屏。 */
    private static final Set<String> DRIFT_LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * 周期性漂移检测：把 {@code Entity} 各通道的「id@对象身份」与启动快照比对，一有变化立刻 ERROR。
     *
     * <p>生产实测：启动自检时通道 id 完全正常（0..7、类池=7），打到战斗中途才出现「两个通道同 id」。
     * 说明漂移是<b>会话中途事件</b>而不是启动顺序问题——这个方法就是给那一刻打时间戳，
     * 好在日志里和 {@code [YizRestore]} 之类的类重定义事件对齐时间线。只读 8 个静态字段，成本可忽略，
     * 挂在藏血发现的后台 ticker（5 秒一次）上跑。</p>
     */
    public static void auditEntityChannelDrift() {
        if (CHANNEL_SNAPSHOT.isEmpty()) return;
        try {
            StringBuilder drift = new StringBuilder();
            for (Field f : Entity.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(null);
                if (!(v instanceof EntityDataAccessor<?> a)) continue;
                String key = "Entity." + f.getName();
                String before = CHANNEL_SNAPSHOT.get(key);
                String now = a.getId() + "@" + Integer.toHexString(System.identityHashCode(v));
                if (before != null && !before.equals(now)) {
                    drift.append(f.getName()).append(": ").append(before).append(" → ").append(now).append("; ");
                }
            }
            if (drift.length() > 0) {
                String sig = drift.toString();
                if (DRIFT_LOGGED.add(sig)) {
                    LOGGER.error("[SynchedEntityData] ⚠ Entity 通道在会话中途漂移: {}；类池[Entity]={} —— "
                            + "通道 id 撞车（Duplicate id value / 读出错类型值崩 tick 或渲染）就是从这一刻开始的",
                        sig, entityPoolId());
                }
            }
        } catch (Throwable ignored) {}
    }

    // ==================== 类型安全默认值 ====================

    /**
     * 值类型与序列化器不匹配时的兜底值；返回 {@code null} 表示"无需修复"（类型正常、或该序列化器
     * 不认识——保守起见不动）。
     *
     * <p><b>必须覆盖 1.20.1 全部原生序列化器</b>：漏一个就等于那个通道没有兜底。生产实测漏了
     * {@link EntityDataSerializers#POSE}：通道 id 撞车后 {@code DATA_POSE} 槽里是
     * {@code DATA_SHARED_FLAGS_ID} 的 {@code Byte}，这里返回 null → 守卫提前放行 → vanilla
     * {@code Entity.getPose()} 抛 {@code ClassCastException: Byte cannot be cast to Pose}，
     * 服务端崩 tick（玩家）、客户端崩渲染（实体）。补上后同一场景只是读到默认姿态，不再崩。</p>
     */
    public static Object defaultFor(EntityDataSerializer<?> ser, Object v) {
        // 基础类型
        if (ser == EntityDataSerializers.INT) return v instanceof Integer ? null : (Object) 0;
        if (ser == EntityDataSerializers.LONG) return v instanceof Long ? null : (Object) 0L;
        if (ser == EntityDataSerializers.FLOAT) return v instanceof Float ? null : (Object) 0.0F;
        if (ser == EntityDataSerializers.BYTE) return v instanceof Byte ? null : (Object) (byte) 0;
        if (ser == EntityDataSerializers.BOOLEAN) return v instanceof Boolean ? null : (Object) false;
        if (ser == EntityDataSerializers.STRING) return v instanceof String ? null : (Object) "";
        if (ser == EntityDataSerializers.OPTIONAL_UNSIGNED_INT) {
            return v instanceof java.util.OptionalInt ? null : (Object) java.util.OptionalInt.empty();
        }
        // 姿态（Entity 的 DATA_POSE 通道；用 Pose.STANDING 而不是 null，避免下游 NPE）
        if (ser == EntityDataSerializers.POSE) {
            return v instanceof net.minecraft.world.entity.Pose ? null : net.minecraft.world.entity.Pose.STANDING;
        }
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
        if (ser == EntityDataSerializers.ROTATIONS) {
            return v instanceof net.minecraft.core.Rotations ? null : new net.minecraft.core.Rotations(0.0F, 0.0F, 0.0F);
        }
        if (ser == EntityDataSerializers.PARTICLE) {
            return v instanceof net.minecraft.core.particles.ParticleOptions ? null
                    : net.minecraft.core.particles.ParticleTypes.CRIT;
        }
        if (ser == EntityDataSerializers.VILLAGER_DATA) {
            return v instanceof net.minecraft.world.entity.npc.VillagerData ? null
                    : new net.minecraft.world.entity.npc.VillagerData(
                        net.minecraft.world.entity.npc.VillagerType.PLAINS,
                        net.minecraft.world.entity.npc.VillagerProfession.NONE, 1);
        }
        if (ser == EntityDataSerializers.CAT_VARIANT) {
            // 猫变体是注册表对象：从内置注册表取默认项（取不到就交给 try/catch 视为"不修"）
            return v instanceof net.minecraft.world.entity.animal.CatVariant ? null
                    : net.minecraft.core.registries.BuiltInRegistries.CAT_VARIANT
                        .getOrThrow(net.minecraft.world.entity.animal.CatVariant.TABBY);
        }
        if (ser == EntityDataSerializers.FROG_VARIANT) {
            return v instanceof net.minecraft.world.entity.animal.FrogVariant ? null
                    : net.minecraft.world.entity.animal.FrogVariant.TEMPERATE;
        }
        if (ser == EntityDataSerializers.PAINTING_VARIANT) {
            return v instanceof net.minecraft.core.Holder ? null
                    : net.minecraft.core.Holder.direct(
                        new net.minecraft.world.entity.decoration.PaintingVariant(16, 16));
        }
        if (ser == EntityDataSerializers.SNIFFER_STATE) {
            return v instanceof net.minecraft.world.entity.animal.sniffer.Sniffer.State ? null
                    : net.minecraft.world.entity.animal.sniffer.Sniffer.State.IDLING;
        }
        if (ser == EntityDataSerializers.VECTOR3) {
            return v instanceof org.joml.Vector3f ? null : new org.joml.Vector3f();
        }
        if (ser == EntityDataSerializers.QUATERNION) {
            return v instanceof org.joml.Quaternionf ? null : new org.joml.Quaternionf();
        }
        return null;
    }
}
