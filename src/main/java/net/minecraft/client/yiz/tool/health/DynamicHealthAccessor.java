package net.minecraft.client.yiz.tool.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用「差值血量 + 独立死亡标记」DataParameter 检测 + 篡改（涨跌多空的动态血防实体分支）。
 *
 * <p>部分实体把血量藏在<b>自定义 DataParameter</b>里，用
 * {@code getHealth() = normal - away} 两个 Float 通道的<b>差值</b>动态计算，且
 * {@code isAlive()/isDeadOrDying()} 读一个<b>独立的 Boolean 死亡标记</b>（不读 getHealth）。
 * 这类实体对「字段级直改 / delta 软压 / 无脑减所有 Float 通道」都免疫。</p>
 *
 * <p>本类用<b>纯行为验证</b>识别（不依赖字段名/类名，可混淆）：</p>
 * <ul>
 *   <li>两个 Float DataAccessor 的差值 {@code |a-b| ≈ getHealth} → 命中「差值血量」；</li>
 *   <li>直写其中一个 {@code +1} 看 getHealth 方向：增 → 被减数（normal），减 → 减数（away）；</li>
 *   <li>一个 Boolean DataAccessor 值 {@code == isDeadOrDying} → 死亡标记。</li>
 * </ul>
 * <p>篡改走 {@link DirectHealthFallback#setFloatChannelValue} 直写 DataItem（绕过目标
 * {@code setExaltedAway} 的 clamp），扣血方向正确：<b>增加 away</b> 而非减少。</p>
 */
public final class DynamicHealthAccessor {

    /** 命中的差值血量通道：away=减数（增加=扣血），normal=被减数（上限），death=死亡标记。 */
    public record Slot(EntityDataAccessor<Float> away, EntityDataAccessor<Float> normal,
                       EntityDataAccessor<Boolean> death) {}

    private static final Map<String, Slot> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> NON_DYNAMIC = ConcurrentHashMap.newKeySet();

    private DynamicHealthAccessor() {}

    // ==================== 检测 ====================

    /** 检测目标是否为「差值血量」实体；是返回 Slot（缓存），否则 null。 */
    public static Slot detect(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        String key = stableClassName(entity.getClass());
        Slot cached = CACHE.get(key);
        if (cached != null) return cached;
        if (NON_DYNAMIC.contains(key)) return null;
        // 已死/无血实例检测结果不可靠（doDetect 会短路返回 null），
        // 不要缓存为「非差值血量」，下次健康实例再检，避免首次检测污染该类缓存
        boolean unhealthy = entity.getHealth() <= 0 || entity.isDeadOrDying();
        // 字节码静态判据优先：识别 getHealth = 两个 Float 来源相减（FSUB），不依赖 getHealth 运行时值
        Slot bytecodeSlot = detectDifferenceByBytecode(entity);
        if (bytecodeSlot != null) {
            CACHE.put(key, bytecodeSlot);
            return bytecodeSlot;
        }
        Slot slot = doDetect(entity);
        if (slot != null) {
            CACHE.put(key, slot);
        } else if (!unhealthy) {
            NON_DYNAMIC.add(key);
        }
        return slot;
    }

    private static Slot doDetect(LivingEntity entity) {
        if (entity.getHealth() <= 0 || entity.isDeadOrDying()) return null;  // 已死无需检测
        try {
            List<Field> floatFields = new ArrayList<>();
            List<Field> boolFields = new ArrayList<>();
            collectAccessorFields(entity, floatFields, boolFields);

            float health = entity.getHealth();
            // 两两组合 Float 通道，找差值 ≈ getHealth
            for (int i = 0; i < floatFields.size(); i++) {
                for (int j = i + 1; j < floatFields.size(); j++) {
                    EntityDataAccessor<Float> a = acc(floatFields.get(i));
                    EntityDataAccessor<Float> b = acc(floatFields.get(j));
                    if (a == null || b == null) continue;
                    float va = entity.getEntityData().get(a);
                    float vb = entity.getEntityData().get(b);
                    float diff = Math.abs(va - vb);
                    if (Math.abs(diff - health) > Math.max(0.5f, health * 0.05f)) continue;
                    // 命中差值 → 区分 normal/away
                    Slot slot = buildSlot(entity, a, b, va, vb, boolFields);
                    if (slot != null) return slot;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 区分 a/b 谁是 normal（被减数）谁是 away（减数），并找死亡标记。
     *  必须<b>双向</b>验证：normal 直写 +1 → getHealth 增，away 直写 +1 → getHealth 减。
     *  否则「当前血量 + 无关 0 值通道」会被误判成差值血量（如 ImmortalGolem 的 REVIVE_TIMES=0）。 */
    private static Slot buildSlot(LivingEntity entity, EntityDataAccessor<Float> a,
                                  EntityDataAccessor<Float> b, float va, float vb,
                                  List<Field> boolFields) {
        if (isNormal(entity, a, va) && isAway(entity, b, vb)) {
            return new Slot(b, a, findDeathMarker(entity, boolFields));
        }
        if (isAway(entity, a, va) && isNormal(entity, b, vb)) {
            return new Slot(a, b, findDeathMarker(entity, boolFields));
        }
        return null;  // 方向判定失败，或非差值血量
    }

    /** 直写 acc +1 看 getHealth 是否增加：增加 → 被减数（normal）。 */
    private static boolean isNormal(LivingEntity entity, EntityDataAccessor<Float> acc, float cur) {
        try {
            float before = entity.getHealth();
            boolean wrote = DirectHealthFallback.setFloatChannelValue(entity, acc, cur + 1.0f, false);
            float after = entity.getHealth();
            DirectHealthFallback.setFloatChannelValue(entity, acc, cur, false);  // 还原
            return wrote && after > before + 0.5f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 直写 acc +1 看 getHealth 是否减少：减少 → 减数（away）。区分「真 away」与「无关 0 值通道」。 */
    private static boolean isAway(LivingEntity entity, EntityDataAccessor<Float> acc, float cur) {
        try {
            float before = entity.getHealth();
            boolean wrote = DirectHealthFallback.setFloatChannelValue(entity, acc, cur + 1.0f, false);
            float after = entity.getHealth();
            DirectHealthFallback.setFloatChannelValue(entity, acc, cur, false);  // 还原
            return wrote && after < before - 0.5f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 找值 == isDeadOrDying 的 Boolean DataAccessor（死亡标记）。 */
    private static EntityDataAccessor<Boolean> findDeathMarker(LivingEntity entity, List<Field> boolFields) {
        boolean dead = entity.isDeadOrDying();
        for (Field f : boolFields) {
            try {
                EntityDataAccessor<Boolean> acc = acc(f);
                if (acc == null) continue;
                Boolean v = entity.getEntityData().get(acc);
                if (v != null && v.booleanValue() == dead) return acc;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 枚举实体类层级所有静态 Float / Boolean DataAccessor 字段。 */
    private static void collectAccessorFields(LivingEntity entity, List<Field> floatFields, List<Field> boolFields) {
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || !Modifier.isStatic(f.getModifiers())) continue;
                if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    EntityDataAccessor<?> acc = (EntityDataAccessor<?>) f.get(null);
                    if (acc == null) continue;
                    if (acc.getSerializer() == EntityDataSerializers.FLOAT) floatFields.add(f);
                    else if (acc.getSerializer() == EntityDataSerializers.BOOLEAN) boolFields.add(f);
                } catch (Throwable ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityDataAccessor<T> acc(Field f) {
        try {
            f.setAccessible(true);
            return (EntityDataAccessor<T>) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 篡改 ====================

    /**
     * 对「差值血量」实体扣 {@code amount} 血：增加 away（减数），clamp 到 normal；
     * 血量耗尽（away ≥ normal）时同步置死亡标记。非差值血量实体返回 false。
     */
    public static boolean tamper(LivingEntity entity, float amount) {
        if (amount <= 0) return false;
        Slot slot = detect(entity);
        if (slot == null) return false;
        try {
            float normal = entity.getEntityData().get(slot.normal());
            float away = entity.getEntityData().get(slot.away());
            float newAway = Math.min(away + amount, normal);  // 扣血 = 增加 away，clamp 到上限
            DirectHealthFallback.setFloatChannelValue(entity, slot.away(), newAway, true);
            if (newAway >= normal && slot.death() != null) {
                // 直写绕开 SynchedEntityData.set：set 会触发第三方 Boss 模组的 SynchedEntityDataMixin
                // flag 操作（Byte/Boolean 写错类型）→ Byte→Boolean 崩溃（与 finishDeathblow/DeathMarkerAccessor 同款坑）。
                DirectHealthFallback.setBooleanChannelValue(entity, slot.death(), true, true);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 字节码静态判据（不依赖 getHealth 运行时值） ====================

    /**
     * 字节码判据：分析 getHealth() 方法体，识别「两个 Float 来源相减（FSUB）」的差值血量结构，
     * 方向由 FSUB 栈序确定（左=被减数/上限，右=减数/损失）。全程不读 getHealth 运行时值，
     * 不被第三方 coremod 对 getHealth 的截断/改写干扰。
     */
    private static Slot detectDifferenceByBytecode(LivingEntity entity) {
        try {
            Class<?> clazz = entity.getClass();
            ClassNode cn = readClassNode(clazz);
            if (cn == null) return null;
            MethodNode getHealth = findMethod(cn, "getHealth", "()F");
            if (getHealth == null) getHealth = findMethod(cn, "m_21223_", "()F");
            if (getHealth == null) return null;
            // 找 FSUB 的两个操作数字段（[0]=被减数 normal，[1]=减数 away）
            String[] fields = findFsubFieldSources(cn, getHealth);
            if (fields == null) return null;
            EntityDataAccessor<Float> normal = resolveAccessorByField(entity, fields[0]);
            EntityDataAccessor<Float> away = resolveAccessorByField(entity, fields[1]);
            if (normal == null || away == null) return null;
            // 死亡标记仍用行为验证（isDeadOrDying 不被 coremod 截断）
            List<Field> boolFields = new ArrayList<>();
            collectAccessorFields(entity, new ArrayList<>(), boolFields);
            EntityDataAccessor<Boolean> death = findDeathMarker(entity, boolFields);
            return new Slot(away, normal, death);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ClassNode readClassNode(Class<?> clazz) {
        try {
            String internalName = clazz.getName().replace('.', '/');
            InputStream in = clazz.getClassLoader().getResourceAsStream(internalName + ".class");
            if (in == null) return null;
            byte[] bytes = in.readAllBytes();
            in.close();
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return cn;
        } catch (Throwable t) {
            return null;
        }
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (name.equals(m.name) && desc.equals(m.desc)) return m;
        }
        return null;
    }

    /** 在 getHealth 方法体里找 FSUB，并往回扫两个「Float 来源」，返回 [被减数字段, 减数字段]。 */
    private static String[] findFsubFieldSources(ClassNode cn, MethodNode m) {
        AbstractInsnNode fsub = null;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn.getOpcode() == Opcodes.FSUB) { fsub = insn; break; }
        }
        if (fsub == null) return null;
        List<String> sources = new ArrayList<>();
        for (AbstractInsnNode insn = fsub.getPrevious(); insn != null && sources.size() < 2; insn = insn.getPrevious()) {
            String src = floatSourceOf(cn, insn);
            if (src != null) sources.add(src);
        }
        if (sources.size() != 2) return null;
        // sources[0] 更靠近 FSUB = 栈顶 = 减数；sources[1] 更远 = 栈底 = 被减数
        return new String[]{sources.get(1), sources.get(0)};
    }

    /** 识别单条指令是否为「返回 Float 的来源」，是则返回其 DataAccessor 字段名。 */
    private static String floatSourceOf(ClassNode cn, AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode min) {
            if (!min.desc.endsWith(")F")) return null;
            return resolveGetterField(cn, min.name, min.desc);
        }
        if (insn instanceof FieldInsnNode fin && fin.getOpcode() == Opcodes.GETSTATIC) {
            if (fin.desc.startsWith("Lnet/minecraft/network/syncher/EntityDataAccessor;")) {
                return fin.name;
            }
        }
        return null;
    }

    /** 递归解析 getter 方法，找其内部第一个读的 EntityDataAccessor 字段名。 */
    private static String resolveGetterField(ClassNode cn, String getterName, String getterDesc) {
        MethodNode getter = findMethod(cn, getterName, getterDesc);
        if (getter == null) return null;
        for (AbstractInsnNode insn : getter.instructions) {
            if (insn.getOpcode() == Opcodes.GETSTATIC) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                if (fin.desc.startsWith("Lnet/minecraft/network/syncher/EntityDataAccessor;")) {
                    return fin.name;
                }
            }
        }
        return null;
    }

    /** 由字段名反射解析静态 EntityDataAccessor 字段。 */
    private static EntityDataAccessor<Float> resolveAccessorByField(LivingEntity entity, String fieldName) {
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof EntityDataAccessor<?> acc
                        && acc.getSerializer() == EntityDataSerializers.FLOAT) {
                    @SuppressWarnings("unchecked")
                    EntityDataAccessor<Float> fa = (EntityDataAccessor<Float>) acc;
                    return fa;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 隐藏类名（带 /0x 内存地址）→ 稳定父类名，普通类原样返回，作缓存 key。 */
    private static String stableClassName(Class<?> c) {
        String name = c.getName();
        int slash = name.indexOf('/');
        return slash > 0 ? name.substring(0, slash) : name;
    }
}
