package net.minecraft.client.yiz.tool.health;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.loading.FMLPaths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体真实血量字段定位器（1.20.1 移植版）—「全能扫描 / 偏移匹配 / 字节码探测」。
 *
 * <p>解决自研血量实体（override getHealth 不走原版）无法被 Delta 通道持久扣血的问题。
 * 对实体类型首个实例做「细微改动 + 偏移匹配」定位真实血量字段，按类缓存到
 * {@code config/yizmodqzk/entity_health_slots.json}。</p>
 */
public final class EntityHealthLocator {

    private static final String FILE_NAME = "yizmodqzk/entity_health_slots.json";

    private static final double TRIGGER_RATIO = 0.0001;

    /** 实体类名 → 血量槽信息。 */
    public record HealthSlot(String className, String fieldName, String type, boolean inverse, String kind) {
        public HealthSlot(String className, String fieldName, String type, boolean inverse) {
            this(className, fieldName, type, inverse, "field");
        }
    }

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, HealthSlot> CACHE = new ConcurrentHashMap<>();

    private static final ThreadLocal<Boolean> SCANNING = ThreadLocal.withInitial(() -> false);

    private EntityHealthLocator() {}

    // ==================== 公共 API ====================

    public static boolean hasSlot(LivingEntity entity) {
        return entity != null && CACHE.containsKey(entity.getClass().getName());
    }

    public static HealthSlot locate(LivingEntity entity) {
        if (entity == null) return null;
        String key = entity.getClass().getName();
        HealthSlot cached = CACHE.get(key);
        if (cached != null) return cached;
        if (SCANNING.get()) return null;
        HealthSlot slot = detectViaBytecode(entity);
        if (slot == null) {
            slot = detectViaDataAccessor(entity); // DataParameter 血量通道（存 DataItem 的自研实体）
        }
        if (slot == null) {
            slot = scan(entity);
        }
        if (slot != null) {
            CACHE.put(key, slot);
            save();
        }
        return slot;
    }

    public static Double readLocated(LivingEntity entity) {
        HealthSlot slot = locate(entity);
        if (slot == null) return null;
        if ("accessor".equals(slot.kind())) {
            EntityDataAccessor<Float> acc = resolveAccessor(slot);
            if (acc == null) return null;
            try {
                return (double) entity.getEntityData().get(acc);
            } catch (Exception e) {
                return null;
            }
        }
        Field f = resolveField(slot);
        if (f == null) return null;
        try {
            return readField(f, entity);
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeLocated(LivingEntity entity, double value) {
        HealthSlot slot = locate(entity);
        if (slot == null) return;
        if ("accessor".equals(slot.kind())) {
            EntityDataAccessor<Float> acc = resolveAccessor(slot);
            if (acc == null) return;
            try {
                entity.getEntityData().set(acc, (float) value);
            } catch (Exception ignored) {}
            return;
        }
        Field f = resolveField(slot);
        if (f == null) return;
        try {
            writeField(f, entity, value);
        } catch (Exception ignored) {}
    }

    /**
     * 持久扣血（经定位到的真实血量字段或 DataParameter 通道）。
     * inverse（totalDamageTaken 类）：字段 +amount；正向（血量存储类）：字段 −amount。
     *
     * @return true 已持久扣血；false 调用方应回退 Delta。
     */
    public static boolean applyPersistentDamage(LivingEntity entity, float amount) {
        if (entity == null || amount <= 0) return false;
        HealthSlot slot = locate(entity);
        if (slot == null) return false;
        if ("accessor".equals(slot.kind())) {
            // DataParameter 血量通道：直接改 DataItem（绕过 set() 限伤），支持 inverse 语义
            EntityDataAccessor<Float> acc = resolveAccessor(slot);
            if (acc == null) return false;
            float healthBefore = entity.getHealth();
            float cur = 0;
            try { cur = entity.getEntityData().get(acc); } catch (Exception e) { return false; }
            float target = slot.inverse() ? cur + amount : cur - amount;
            boolean applied = DirectHealthFallback.damageFloatChannel(entity, acc, slot.inverse() ? amount : -amount);
            if (!applied) return false;
            float healthAfter = entity.getHealth();
            if (healthBefore - healthAfter < Math.min(amount * 0.5f, healthBefore)) {
                // 写后验证失败 → 回滚（还原旧值）
                try { entity.getEntityData().set(acc, cur); } catch (Exception ignored) {}
                CACHE.remove(entity.getClass().getName());
                save();
                return false;
            }
            VitalitySeveranceHandler.updateFieldBaseline(entity);
            HealthWriteGuard.updateBaseline(entity);
            return true;
        }
        Field f = resolveField(slot);
        if (f == null) return false;
        try {
            double cur = readField(f, entity);
            double next = slot.inverse() ? cur + amount : cur - amount;
            float healthBefore = entity.getHealth();
            writeField(f, entity, next);
            float healthAfter = entity.getHealth();
            if (healthBefore - healthAfter < Math.min(amount * 0.5f, healthBefore)) {
                writeField(f, entity, cur); // 回滚
                CACHE.remove(entity.getClass().getName());
                save();
                return false;
            }
            VitalitySeveranceHandler.updateFieldBaseline(entity);
            HealthWriteGuard.updateBaseline(entity);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 扫描定位 ====================

    private static HealthSlot scan(LivingEntity entity) {
        if (entity.level().isClientSide()) return null;
        float delta = (float) (entity.getMaxHealth() * TRIGGER_RATIO);
        if (delta <= 0) return null;

        List<Field> fields = collectNumericFields(entity.getClass());
        if (fields.isEmpty()) return null;

        SCANNING.set(true);
        try {
            Map<Field, Double> before = snapshot(entity, fields);
            entity.hurt(entity.damageSources().generic(), delta);
            Map<Field, Double> after = snapshot(entity, fields);

            double tolerance = Math.max(delta * 0.5, 1e-4);
            for (Field f : fields) {
                double b = before.getOrDefault(f, 0.0);
                double a = after.getOrDefault(f, 0.0);
                double diff = a - b;
                if (Math.abs(diff) < 1e-9) continue;
                boolean inverse;
                if (Math.abs(diff - delta) <= tolerance) {
                    inverse = true;
                } else if (Math.abs(diff + delta) <= tolerance) {
                    inverse = false;
                } else {
                    continue;
                }
                if (isRealHealthField(entity, f, delta, inverse)) {
                    return new HealthSlot(entity.getClass().getName(), f.getName(), typeName(f), inverse);
                }
            }
        } finally {
            SCANNING.remove();
        }
        return null;
    }

    // ==================== 字节码探测（补充路径） ====================

    /** 分析 getHealth() 字节码，定位其直接返回的 float/double 字段（官方映射名 getHealth 恒定）。 */
    private static HealthSlot detectViaBytecode(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        try {
            Class<?> clazz = entity.getClass();
            String internalName = clazz.getName().replace('.', '/');
            byte[] bytes;
            try (InputStream in = clazz.getClassLoader().getResourceAsStream(internalName + ".class")) {
                if (in == null) return null;
                bytes = in.readAllBytes();
            }
            if (bytes.length == 0) return null;

            ClassReader cr = new ClassReader(bytes);
            String[] found = new String[1];
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!"getHealth".equals(name) || !"()F".equals(descriptor)) {
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name2, String descriptor2) {
                            if (found[0] != null) return;
                            if ((opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC)
                                    && ("F".equals(descriptor2) || "D".equals(descriptor2))) {
                                found[0] = name2;
                            }
                            super.visitFieldInsn(opcode, owner, name2, descriptor2);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (found[0] == null) return null;
            Field f = findFieldInHierarchy(clazz.getName(), found[0]);
            if (f == null) return null;
            return new HealthSlot(clazz.getName(), found[0], typeName(f), false);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * DataParameter 血量通道探测（补充路径）。
     *
     * <p>扫实体类层级所有静态 {@link EntityDataAccessor}&lt;Float&gt; 字段，字段名含 health
     * 且不含 max（血量存储通道的通用命名，仿自研血量实体的惯例），作为血量 DataItem 槽。
     * 覆盖「血量存 DataParameter（DataItem）而非普通字段」的自研实体。</p>
     *
     * <p>优先级：本路径在 {@link #scan}（偏移匹配普通字段）之前执行——若实体把血量藏在
     * DataParameter，字节码探测找不到 GETFIELD float 字段，字段偏移匹配也扫不到。</p>
     */
    private static HealthSlot detectViaDataAccessor(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        try {
            for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    int mod = f.getModifiers();
                    if (!Modifier.isStatic(mod)) continue;
                    if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                    String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!name.contains("health") || name.contains("max")) continue;
                    try {
                        f.setAccessible(true);
                        EntityDataAccessor<?> acc = (EntityDataAccessor<?>) f.get(null);
                        if (acc == null) continue;
                        if (acc.getSerializer() != EntityDataSerializers.FLOAT) continue;
                        // 该通道必须确实存着与 getHealth 一致的血量值（避免误判 MAX_HEALTH/LAST_HURT 等）
                        float channelVal;
                        float getHealthVal;
                        try {
                            @SuppressWarnings("unchecked")
                            EntityDataAccessor<Float> fAcc = (EntityDataAccessor<Float>) acc;
                            channelVal = entity.getEntityData().get(fAcc);
                            getHealthVal = entity.getHealth();
                        } catch (Exception e) {
                            continue;
                        }
                        if (Float.isNaN(channelVal) || Float.isNaN(getHealthVal)) continue;
                        if (Math.abs(channelVal - getHealthVal) <= 0.001f
                                || (getHealthVal > 0 && Math.abs(channelVal - getHealthVal) <= getHealthVal * 0.05f)) {
                            return new HealthSlot(entity.getClass().getName(), f.getName(), "accessor", false, "accessor");
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static EntityDataAccessor<Float> resolveAccessor(HealthSlot slot) {
        try {
            Class<?> c = Class.forName(slot.className());
            for (Class<?> cl = c; cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                try {
                    Field f = cl.getDeclaredField(slot.fieldName());
                    f.setAccessible(true);
                    return (EntityDataAccessor<Float>) f.get(null);
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (ClassNotFoundException | ClassCastException | IllegalAccessException ignored) {}
        return null;
    }

    private static boolean isRealHealthField(LivingEntity entity, Field f, double delta, boolean inverse) {
        try {
            double cur = readField(f, entity);
            float healthBefore = entity.getHealth();
            double probe = inverse ? cur + delta : cur - delta;
            writeField(f, entity, probe);
            float healthAfter = entity.getHealth();
            writeField(f, entity, cur);
            return healthBefore - healthAfter >= delta * 0.5f;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 反射工具 ====================

    private static Field resolveField(HealthSlot slot) {
        String key = slot.className() + "#" + slot.fieldName();
        Field f = FIELD_CACHE.get(key);
        if (f == null) {
            f = findFieldInHierarchy(slot.className(), slot.fieldName());
            if (f != null) FIELD_CACHE.put(key, f);
        }
        return f;
    }

    private static Field findFieldInHierarchy(String className, String fieldName) {
        try {
            Class<?> c = Class.forName(className);
            for (Class<?> cl = c; cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                try {
                    Field f = cl.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (ClassNotFoundException ignored) {}
        return null;
    }

    private static List<Field> collectNumericFields(Class<?> clazz) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != LivingEntity.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) continue;
                Class<?> t = f.getType();
                if (t == float.class || t == double.class || t == int.class || t == long.class) {
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (Exception ignored) {}
                }
            }
        }
        return out;
    }

    private static Map<Field, Double> snapshot(LivingEntity entity, List<Field> fields) {
        Map<Field, Double> map = new HashMap<>();
        for (Field f : fields) {
            try { map.put(f, readField(f, entity)); } catch (Exception ignored) {}
        }
        return map;
    }

    private static double readField(Field f, Object target) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == float.class) return f.getFloat(target);
        if (t == double.class) return f.getDouble(target);
        if (t == int.class) return f.getInt(target);
        if (t == long.class) return f.getLong(target);
        return 0;
    }

    private static void writeField(Field f, Object target, double v) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == float.class) f.setFloat(target, (float) v);
        else if (t == double.class) f.setDouble(target, v);
        else if (t == int.class) f.setInt(target, (int) v);
        else if (t == long.class) f.setLong(target, (long) v);
    }

    private static String typeName(Field f) {
        return f.getType().getSimpleName();
    }

    // ==================== JSON 缓存 ====================

    public static void load() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            JsonObject slots = root.getAsJsonObject("slots");
            if (slots == null) return;
            for (String key : slots.keySet()) {
                JsonObject o = slots.getAsJsonObject(key);
                String kind = o.has("kind") ? o.get("kind").getAsString() : "field";
                CACHE.put(key, new HealthSlot(
                    o.get("class").getAsString(),
                    o.get("field").getAsString(),
                    o.get("type").getAsString(),
                    o.has("inverse") && o.get("inverse").getAsBoolean(),
                    kind));
            }
        } catch (Exception ignored) {}
    }

    public static void save() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            Files.createDirectories(p.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("_version", 1);
            JsonObject slots = new JsonObject();
            CACHE.forEach((k, s) -> {
                JsonObject o = new JsonObject();
                o.addProperty("class", s.className());
                o.addProperty("field", s.fieldName());
                o.addProperty("type", s.type());
                o.addProperty("inverse", s.inverse());
                o.addProperty("kind", s.kind() == null ? "field" : s.kind());
                slots.add(k, o);
            });
            root.add("slots", slots);
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ignored) {}
    }
}
