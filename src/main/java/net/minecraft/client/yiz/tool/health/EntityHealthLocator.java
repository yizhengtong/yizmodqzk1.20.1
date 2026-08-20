package net.minecraft.client.yiz.tool.health;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.yiz.tool.health.codec.BlackBoxInverseSolver;
import net.minecraft.client.yiz.tool.health.codec.EncodedValueCodec;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体真实血量定位器（1.20.1 P0 扩展版）—「全能扫描 / 偏移匹配 / 字节码探测 / 数值通道 / 混淆串 / 编码反解」。
 *
 * <p>解决自研血量实体（override getHealth 不走原版）无法被 Delta 通道持久扣血的问题。
 * 对实体类型首个健康实例做「细微改动 + 行为验证」定位真实血量槽，按类缓存到
 * {@code config/yizmodqzk/entity_health_slots.json}。</p>
 *
 * <p>P0 扩展（均为类型特征 + 行为验证驱动，无类名/字段名假设）：</p>
 * <ul>
 *   <li>{@code naccessor}：Int/Long/Double DataParameter 数值通道（如 INT 血量通道）；</li>
 *   <li>{@code string}：String DataParameter 前缀/后缀数值编码通道（如 {@code "HEC"+血量}）；</li>
 *   <li>{@code codec}：编码字段（XOR 切片 / 缩放偏移），由 {@link BlackBoxInverseSolver}
 *       黑箱反函数求解，meta 携带变换描述符；</li>
 *   <li>累加器配对（{@code pair} meta）：inverse 命中/编码字段的等值镜像字段（如
 *       {@code lastAcc}），直写时同步写，避免进度钳/读取钳把直写拉回。</li>
 * </ul>
 *
 * <p>所有 kind 的 {@code readLocated} 返回<b>逻辑血量</b>（越大越健康），
 * {@code writeLocated} 接收逻辑血量目标，内部按 kind 反解存储值。</p>
 */
public final class EntityHealthLocator {

    private static final String FILE_NAME = "yizmodqzk/entity_health_slots.json";

    private static final double TRIGGER_RATIO = 0.0001;

    /** codec 扫描每个类最多探测的字段数（防性能黑洞）。 */
    private static final int MAX_CODEC_PROBE_FIELDS = 24;

    /** 通道行为验证每个类最多探测的候选通道数。 */
    private static final int MAX_CHANNEL_PROBES = 16;

    /** 实体类名 → 血量槽信息（含 kind 与 meta）。 */
    public record HealthSlot(String className, String fieldName, String type, boolean inverse, String kind, String meta) {
        public HealthSlot(String className, String fieldName, String type, boolean inverse, String kind) {
            this(className, fieldName, type, inverse, kind, "");
        }

        public HealthSlot(String className, String fieldName, String type, boolean inverse) {
            this(className, fieldName, type, inverse, "field", "");
        }
    }

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, HealthSlot> CACHE = new ConcurrentHashMap<>();
    /** 已确认无槽的实体类（负缓存，避免每次攻击重复全量扫描；仅健康实例探测失败才入）。 */
    private static final Set<String> NON_SLOT_CLASSES = ConcurrentHashMap.newKeySet();
    /** 累加器配对字段缓存：主字段 → 镜像字段名（空串=无配对）。 */
    private static final Map<String, String> MIRROR_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> MIRROR_NEGATIVE = ConcurrentHashMap.newKeySet();
    /** 每类定位结果日志（成功/失败各打一次，防刷屏）。 */
    private static final Set<String> SLOT_LOG_DONE = ConcurrentHashMap.newKeySet();
    private static final Set<String> NO_SLOT_LOG_DONE = ConcurrentHashMap.newKeySet();

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;

    private static final ThreadLocal<Boolean> SCANNING = ThreadLocal.withInitial(() -> false);

    private EntityHealthLocator() {}

    // ==================== 公共 API ====================

    public static boolean hasSlot(LivingEntity entity) {
        return entity != null && CACHE.containsKey(entity.getClass().getName());
    }

    /** 声明式注入槽缓存（health_overrides.json 或外部模组调用）。 */
    public static void declareSlot(String className, String fieldName, String kind, boolean inverse, String meta) {
        if (className == null || className.isEmpty() || fieldName == null || fieldName.isEmpty()) return;
        CACHE.put(className, new HealthSlot(className, fieldName, kind, inverse, kind, meta == null ? "" : meta));
        NON_SLOT_CLASSES.remove(className);
        save();
    }

    public static HealthSlot locate(LivingEntity entity) {
        if (entity == null) return null;
        String key = entity.getClass().getName();
        HealthSlot cached = CACHE.get(key);
        if (cached != null) return cached;
        if (NON_SLOT_CLASSES.contains(key)) return null;
        if (SCANNING.get()) return null;
        boolean healthy = !entity.isDeadOrDying() && entity.getHealth() > 0;
        HealthSlot slot = detectViaDataItemsExtended(entity);      // itemsById 行为验证（命名无关）
        if (slot == null) {
            slot = detectViaDataAccessorExtended(entity);          // 广泛命名 + 数值/串序列化器 + 行为验证
        }
        if (slot == null) {
            slot = detectViaBytecode(entity);                      // getHealth/setHealth 字节码 + 行为复核
        }
        if (slot == null) {
            slot = scan(entity);                                    // per-field 行为验证（无 hurt 副作用）
        }
        if (slot == null) {
            slot = scanCodecFields(entity);                         // 编码字段黑箱反解（XOR/缩放）
        }
        if (slot != null) {
            CACHE.put(key, slot);
            save();
            if (SLOT_LOG_DONE.add(key)) {
                LOGGER.info("[EHL] 定位成功 {}: kind={} field={} inverse={} meta={}",
                    key, slot.kind(), slot.fieldName(), slot.inverse(), slot.meta());
            }
        } else if (healthy) {
            NON_SLOT_CLASSES.add(key);
            if (NO_SLOT_LOG_DONE.add(key)) {
                LOGGER.warn("[EHL] 定位失败(无槽) {}: 该类走 delta 软压/数值通道兜底", key);
            }
        }
        return slot;
    }

    /** 读取实体的逻辑血量（越大越健康；kind 无关）。未定位返回 null。 */
    public static Double readLocated(LivingEntity entity) {
        if (entity == null) return null;
        HealthSlot slot = locate(entity);
        if (slot == null) return null;
        return readLocated(entity, slot);
    }

    /** 把实体的逻辑血量写到目标值（kind 无关，内部反解存储值）。 */
    public static void writeLocated(LivingEntity entity, double value) {
        if (entity == null) return;
        HealthSlot slot = locate(entity);
        if (slot == null) return;
        writeLocated(entity, slot, value);
    }

    /**
     * 持久扣血（经定位到的真实血量槽）。逻辑语义：读逻辑血量 → 减 amount → 写回。
     *
     * @return true 已持久扣血；false 调用方应回退 Delta。
     */
    public static boolean applyPersistentDamage(LivingEntity entity, float amount) {
        if (entity == null || amount <= 0) return false;
        HealthSlot slot = locate(entity);
        if (slot == null) {
            if (NO_SLOT_LOG_DONE.contains(entity.getClass().getName())) {
                LOGGER.debug("[EHL] applyPersistentDamage 无槽跳过 {}", entity.getClass().getName());
            }
            return false;
        }
        Double logical = readLocated(entity, slot);
        if (logical == null || !Double.isFinite(logical)) {
            LOGGER.warn("[EHL] 读槽失败 {} kind={} field={} → 回退 delta", entity.getClass().getName(),
                slot.kind(), slot.fieldName());
            return false;
        }
        float healthBefore = entity.getHealth();
        // 已死实体：写与不写无意义，直接判定成功，避免「验证失败→清缓存→重定位」空转
        if (healthBefore <= 0 || entity.isDeadOrDying()) return true;
        double target = Math.max(0, logical - amount);
        boolean ok = writeLocated(entity, slot, target);
        float healthAfter = entity.getHealth();
        if (!ok || healthBefore - healthAfter < Math.min(amount * 0.5f, Math.max(healthBefore, 0.01f))) {
            // 写后验证失败 → 回滚（还原旧值）
            try {
                writeLocated(entity, slot, logical);
            } catch (Throwable ignored) {}
            CACHE.remove(entity.getClass().getName());
            save();
            LOGGER.warn("[EHL] 写槽验证失败 {} kind={} field={} before={} after={} amount={} → 回滚并清除缓存",
                entity.getClass().getName(), slot.kind(), slot.fieldName(), healthBefore, healthAfter, amount);
            return false;
        }
        VitalitySeveranceHandler.updateFieldBaseline(entity);
        HealthWriteGuard.updateBaseline(entity);
        return true;
    }

    // ==================== 逻辑血量读写（按 kind 分派） ====================

    private static Double readLocated(LivingEntity entity, HealthSlot slot) {
        try {
            switch (slot.kind()) {
                case "naccessor": {
                    EntityDataAccessor<?> acc = resolveAccessor(slot);
                    if (acc == null) return null;
                    Number n = DirectHealthFallback.readNumericChannel(entity, acc);
                    return n == null ? null : n.doubleValue();
                }
                case "string": {
                    EntityDataAccessor<?> acc = resolveAccessor(slot);
                    if (acc == null) return null;
                    String[] cur = {null};
                    DirectHealthFallback.forEachStringItem(entity, (a, v, item) -> {
                        if (a.getId() == acc.getId()) cur[0] = v;
                    });
                    if (cur[0] == null) return null;
                    double[] parsed = parseNumberFromString(cur[0]);
                    if (parsed == null) return null;
                    // 声明式 inverse 串（meta: b=上限;inverse=true）：逻辑血量 = b − 解析值
                    String bStr = metaGet(slot.meta(), "b");
                    if ("true".equals(metaGet(slot.meta(), "inverse")) && bStr != null) {
                        double b = Double.parseDouble(bStr);
                        return b - parsed[0];
                    }
                    return parsed[0];
                }
                case "codec": {
                    // 存储值解码（免疫 agent specialGetHealth / delta / 死亡累积干扰）：
                    // 直接按编码描述符把存储字段反解为逻辑血量
                    EncodedValueCodec.Solution sol = EncodedValueCodec.Solution.fromMeta(slot.meta());
                    if (sol == null) return null;
                    Field f = resolveField(slot);
                    if (f == null) return null;
                    double raw = readField(f, entity);
                    return sol.decode(raw);
                }
                case "accessor": {
                    EntityDataAccessor<Float> acc = resolveAccessor(slot);
                    if (acc == null) return null;
                    try {
                        return (double) entity.getEntityData().get(acc);
                    } catch (Exception e) {
                        return null;
                    }
                }
                default: { // field
                    Field f = resolveField(slot);
                    if (f == null) return null;
                    double raw = readField(f, entity);
                    if (slot.inverse()) {
                        // 累加器型：逻辑血量 = B − raw，B = raw + 当前血量
                        double h = entity.getHealth();
                        return (Double.isFinite(h) && h > 0) ? raw + h : raw;
                    }
                    return raw;
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean writeLocated(LivingEntity entity, HealthSlot slot, double logical) {
        try {
            switch (slot.kind()) {
                case "naccessor": {
                    EntityDataAccessor<?> acc = resolveAccessor(slot);
                    if (acc == null) return false;
                    Number ref = DirectHealthFallback.readNumericChannel(entity, acc);
                    if (ref == null) return false;
                    return DirectHealthFallback.setNumericChannelValue(entity, acc, coerceNumber(logical, ref), true);
                }
                case "string": {
                    EntityDataAccessor<?> acc = resolveAccessor(slot);
                    if (acc == null) return false;
                    String[] cur = {null};
                    DirectHealthFallback.forEachStringItem(entity, (a, v, item) -> {
                        if (a.getId() == acc.getId()) cur[0] = v;
                    });
                    if (cur[0] == null) return false;
                    double[] parsed = parseNumberFromString(cur[0]);
                    if (parsed == null) return false;
                    // 声明式 inverse 串（meta: b=上限;inverse=true）：存储值 = b − 逻辑血量
                    double store = logical;
                    String bStr = metaGet(slot.meta(), "b");
                    if ("true".equals(metaGet(slot.meta(), "inverse")) && bStr != null) {
                        double b = Double.parseDouble(bStr);
                        store = b - logical;
                    }
                    String rebuilt = rebuildStringNumber(cur[0], parsed, store);
                    return DirectHealthFallback.setStringChannelValue(entity, acc, rebuilt, true);
                }
                case "codec": {
                    EncodedValueCodec.Solution sol = EncodedValueCodec.Solution.fromMeta(slot.meta());
                    if (sol == null) return false;
                    Field f = resolveField(slot);
                    if (f == null) return false;
                    // 逻辑上限钳制（INVERSE/XOR 的 b 即最大血量 B）
                    double t = (sol.transform() == EncodedValueCodec.Transform.INVERSE
                            || sol.transform() == EncodedValueCodec.Transform.XOR)
                            ? Math.min(logical, sol.b()) : logical;
                    double w = sol.encode(t);
                    if (!Double.isFinite(w)) return false;
                    writeFieldWithMirror(entity, f, w, metaGet(slot.meta(), "pair"));
                    return true;
                }
                case "accessor": {
                    EntityDataAccessor<Float> acc = resolveAccessor(slot);
                    if (acc == null) return false;
                    return DirectHealthFallback.setFloatChannelValue(entity, acc, (float) logical, true);
                }
                default: { // field
                    Field f = resolveField(slot);
                    if (f == null) return false;
                    double cur = readField(f, entity);
                    double raw;
                    if (slot.inverse()) {
                        double h = entity.getHealth();
                        if (Double.isFinite(h) && h > 0) {
                            raw = cur + h - logical;   // B = cur + h，存储 = B − 逻辑血量
                        } else {
                            raw = cur - logical;
                        }
                    } else {
                        raw = logical;
                    }
                    writeFieldWithMirror(entity, f, raw, metaGet(slot.meta(), "pair"));
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 通道扫描定位（数值 + 字符串） ====================

    /**
     * itemsById 行为验证定位（命名无关）：遍历实体所有 Float/Int/Long/Double DataItem
     * （值域过滤 + 直写通道看 getHealth 是否跟随），随后遍历 String DataItem
     * （前缀/后缀数值解析 + 写读往返验证）。对「血量存 DataParameter 但字段名不像 health」
     * 的自研实体也能命中。
     */
    private static HealthSlot detectViaDataItemsExtended(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        float maxHp = entity.getMaxHealth();
        if (maxHp <= 0) return null;
        if (SCANNING.get()) return null;
        SCANNING.set(true);
        try {
            HealthSlot[] found = {null};
            int[] probed = {0};
            // 数值通道
            DirectHealthFallback.forEachNumericItem(entity, (acc, value, item) -> {
                if (found[0] != null || probed[0] >= MAX_CHANNEL_PROBES) return;
                double v = value.doubleValue();
                if (v <= 0 || v > maxHp * 1.5f) return;                                // 值域过滤
                if (acc.getSerializer() == EntityDataSerializers.FLOAT) {
                    if (acc.getId() == DirectHealthFallback.DELTA_ACCESSOR_ID) return;       // 排除 delta 通道
                    if (DirectHealthFallback.VANILLA_HEALTH_ACCESSOR != null
                            && acc.getId() == DirectHealthFallback.VANILLA_HEALTH_ACCESSOR.getId()) return; // 原版通道走其它路径
                }
                probed[0]++;
                if (verifyNumericChannelFollows(entity, acc, value, +1)
                        || verifyNumericChannelFollows(entity, acc, value, -1)) {
                    String fieldName = findAccessorFieldName(entity, acc);
                    if (fieldName != null) {
                        String kind = acc.getSerializer() == EntityDataSerializers.FLOAT ? "accessor" : "naccessor";
                        found[0] = new HealthSlot(entity.getClass().getName(), fieldName, "accessor", false, kind, "");
                    }
                }
            });
            if (found[0] != null) return found[0];
            // 字符串通道（前缀/后缀数值编码）
            DirectHealthFallback.forEachStringItem(entity, (acc, value, item) -> {
                if (found[0] != null || probed[0] >= MAX_CHANNEL_PROBES) return;
                double[] parsed = parseNumberFromString(value);
                if (parsed == null) return;
                if (parsed[0] < 0 || parsed[0] > maxHp * 1.5f) return;
                probed[0]++;
                if (verifyStringChannelFollows(entity, acc, value, parsed, +1)
                        || verifyStringChannelFollows(entity, acc, value, parsed, -1)) {
                    String fieldName = findAccessorFieldName(entity, acc);
                    if (fieldName != null) {
                        found[0] = new HealthSlot(entity.getClass().getName(), fieldName, "string", false, "string", "");
                    }
                }
            });
            return found[0];
        } finally {
            SCANNING.remove();
        }
    }

    /**
     * 静态 EntityDataAccessor 字段扫描（补充路径）：任意数值/字符串序列化器 +
     * 通道值≈getHealth 或行为验证。生产环境字段名是 SRG（f_xxx）无法按名字判断，
     * 靠 serializer + 行为验证兜底。
     */
    private static HealthSlot detectViaDataAccessorExtended(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        try {
            for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    int mod = f.getModifiers();
                    if (!Modifier.isStatic(mod)) continue;
                    if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        EntityDataAccessor<?> acc = (EntityDataAccessor<?>) f.get(null);
                        if (acc == null) continue;
                        var ser = acc.getSerializer();
                        if (ser == EntityDataSerializers.FLOAT) {
                            // 排除本家 delta 通道与 vanilla 血量通道（自研血量实体 getHealth 可能与
                            // vanilla 通道值偶合相等，「值匹配捷径」会误判 → 一律只认行为验证）
                            if (acc.getId() == DirectHealthFallback.DELTA_ACCESSOR_ID) continue;
                            if (DirectHealthFallback.VANILLA_HEALTH_ACCESSOR != null
                                    && acc.getId() == DirectHealthFallback.VANILLA_HEALTH_ACCESSOR.getId()) continue;
                            // 行为验证（直写通道 ±1，getHealth 跟随）：原版/常见实体直接命中
                            if (verifyChannelFollowsGetHealth(entity, (EntityDataAccessor<Float>) acc)) {
                                return new HealthSlot(entity.getClass().getName(), f.getName(), "accessor", false, "accessor", "");
                            }
                        } else if (ser == EntityDataSerializers.INT || ser == EntityDataSerializers.LONG) {
                            Object v = entity.getEntityData().get(acc);
                            if (v instanceof Number n) {
                                double d = n.doubleValue();
                                float maxHp = entity.getMaxHealth();
                                if (maxHp > 0 && d > 0 && d <= maxHp * 1.5f
                                        && (verifyNumericChannelFollows(entity, acc, n, +1)
                                            || verifyNumericChannelFollows(entity, acc, n, -1))) {
                                    return new HealthSlot(entity.getClass().getName(), f.getName(), "naccessor", false, "naccessor", "");
                                }
                            }
                        } else if (ser == EntityDataSerializers.STRING) {
                            Object v = entity.getEntityData().get(acc);
                            if (v instanceof String s) {
                                double[] parsed = parseNumberFromString(s);
                                float maxHp = entity.getMaxHealth();
                                if (parsed != null && maxHp > 0 && parsed[0] >= 0 && parsed[0] <= maxHp * 1.5f
                                        && (verifyStringChannelFollows(entity, acc, s, parsed, +1)
                                            || verifyStringChannelFollows(entity, acc, s, parsed, -1))) {
                                    return new HealthSlot(entity.getClass().getName(), f.getName(), "string", false, "string", "");
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 字节码探测（补充路径 + 行为复核） ====================

    /**
     * 分析 getHealth()/setHealth() 字节码，定位其直接访问的 float/double 字段
     * （官方映射名 getHealth 恒定；SRG m_21223_/m_21153_）。定位结果<b>必须</b>通过
     * 行为复核（直写 ±delta 看 getHealth 跟随）——编码字段（XOR 切片等）会被复核打回，
     * 交给 {@link #scanCodecFields} 黑箱反解，避免把编码字段误当直映字段缓存。
     */
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
                    // getHealth()F：读血量字段（GETFIELD）。双名匹配（official + SRG），生产字节码方法名是 m_21223_
                    boolean isGet = ("getHealth".equals(name) || "m_21223_".equals(name)) && "()F".equals(descriptor);
                    // setHealth(F)V：写血量字段（PUTFIELD）——对 getHealth 复合/返回常量（Infinity）但
                    //    override setHealth 写真实字段的实体也能定位真实血量槽（2026-08-12 定位增强）
                    boolean isSet = ("setHealth".equals(name) || "m_21153_".equals(name)) && "(F)V".equals(descriptor);
                    if (!isGet && !isSet) {
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name2, String descriptor2) {
                            if (found[0] != null) return;
                            boolean isFloat = "F".equals(descriptor2) || "D".equals(descriptor2);
                            if (isGet && (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC) && isFloat) {
                                found[0] = name2;
                            } else if (isSet && opcode == Opcodes.PUTFIELD && isFloat) {
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
            // final/static 字段是语义常量（如 BOSSMX 上限、配置常量），不是可写血量槽 → 拒绝
            int fmod = f.getModifiers();
            if (Modifier.isFinal(fmod) || Modifier.isStatic(fmod)) return null;
            // 行为复核：直写 ±delta 看 getHealth 是否跟随（防编码字段误判）
            float delta = (float) (entity.getMaxHealth() * TRIGGER_RATIO);
            if (delta <= 0) return null;
            if (isRealHealthField(entity, f, delta, false)) {
                return new HealthSlot(clazz.getName(), found[0], typeName(f), false);
            }
            if (isRealHealthField(entity, f, delta, true)) {
                return new HealthSlot(clazz.getName(), found[0], typeName(f), true);
            }
            return null; // 行为不跟随 → 交给 scan / scanCodecFields
        } catch (Throwable e) {
            return null;
        }
    }

    // ==================== 普通字段扫描 ====================

    private static HealthSlot scan(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        float delta = (float) (entity.getMaxHealth() * TRIGGER_RATIO);
        if (delta <= 0) return null;

        List<Field> fields = collectNumericFields(entity.getClass());
        if (fields.isEmpty()) return null;

        SCANNING.set(true);
        try {
            // per-field 行为验证：改字段 ±delta 看 getHealth 是否跟随（不再主动 hurt，零伤害副作用；
            // 对 override getHealth 读该字段的自研实体也能命中）
            for (Field f : fields) {
                if (isRealHealthField(entity, f, delta, false)) {
                    return new HealthSlot(entity.getClass().getName(), f.getName(), typeName(f), false);
                }
                if (isRealHealthField(entity, f, delta, true)) {
                    // 累加器型（inverse）：找等值镜像字段（lastAcc 类）配对，直写时同步写
                    String pair = findMirrorPairField(entity, f);
                    String meta = pair != null ? "pair=" + pair : "";
                    return new HealthSlot(entity.getClass().getName(), f.getName(), typeName(f), true, "field", meta);
                }
            }
        } finally {
            SCANNING.remove();
        }
        return null;
    }

    // ==================== 编码字段扫描（黑箱反解） ====================

    /**
     * 编码字段探测：对普通字段扫描未命中的数值字段，用 {@link BlackBoxInverseSolver}
     * 推断存储值 ↔ 逻辑血量变换（XOR 切片 / 缩放偏移 / 反向累加），确认后输出
     * {@code codec} 槽，meta 携带变换描述符与累加器配对。
     */
    private static HealthSlot scanCodecFields(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        List<Field> fields = collectNumericFields(entity.getClass());
        if (fields.isEmpty()) return null;
        if (SCANNING.get()) return null;
        SCANNING.set(true);
        try {
            int probed = 0;
            for (Field f : fields) {
                if (probed >= MAX_CODEC_PROBE_FIELDS) break;
                double w0;
                try {
                    w0 = readField(f, entity);
                } catch (Exception e) {
                    continue;
                }
                if (!Double.isFinite(w0)) continue;
                probed++;
                BlackBoxInverseSolver.StorageAccess access = new FieldStorageAccess(f, entity);
                EncodedValueCodec.Solution sol = BlackBoxInverseSolver.infer(entity, access);
                if (sol == null) continue;
                String meta = sol.toMeta();
                String pair = findMirrorPairField(entity, f);
                if (pair != null) meta = meta + ";pair=" + pair;
                return new HealthSlot(entity.getClass().getName(), f.getName(), typeName(f), false, "codec", meta);
            }
        } finally {
            SCANNING.remove();
        }
        return null;
    }

    /** 字段存储访问器（供黑箱求解器探针使用）。 */
    private static final class FieldStorageAccess implements BlackBoxInverseSolver.StorageAccess {
        private final Field field;
        private final Object target;

        FieldStorageAccess(Field field, Object target) {
            this.field = field;
            this.target = target;
        }

        @Override
        public double read() {
            try {
                return readField(field, target);
            } catch (Exception e) {
                return Double.NaN;
            }
        }

        @Override
        public boolean write(double v) {
            try {
                writeField(field, target, v);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ==================== 行为验证 ====================

    /** 数值通道行为验证：直写通道 cur±1，读 getHealth 是否跟随（随后还原，不触发 dirty 同步）。 */
    private static boolean verifyNumericChannelFollows(LivingEntity entity, EntityDataAccessor<?> acc,
                                                       Number curVal, int dir) {
        try {
            double cur = curVal.doubleValue();
            float before = entity.getHealth();
            boolean wrote = DirectHealthFallback.setNumericChannelValue(entity, acc, coerceNumber(cur + dir, curVal), false);
            float after = entity.getHealth();
            DirectHealthFallback.setNumericChannelValue(entity, acc, coerceNumber(cur, curVal), false); // 还原
            return wrote && before > 0 && Math.abs((after - before) - dir) < 0.6f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 字符串通道行为验证：改写前缀/后缀数值 ±1，读 getHealth 是否跟随（随后还原）。 */
    private static boolean verifyStringChannelFollows(LivingEntity entity, EntityDataAccessor<?> acc,
                                                      String original, double[] parsed, int dir) {
        try {
            float before = entity.getHealth();
            String probe = rebuildStringNumber(original, parsed, parsed[0] + dir);
            boolean wrote = DirectHealthFallback.setStringChannelValue(entity, acc, probe, false);
            float after = entity.getHealth();
            DirectHealthFallback.setStringChannelValue(entity, acc, original, false); // 还原
            return wrote && before > 0 && Math.abs((after - before) - dir) < 0.6f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 原有 Float 通道行为验证：直写通道 cur−1，读 getHealth 是否跟随减少，随后还原。 */
    private static boolean verifyChannelFollowsGetHealth(LivingEntity entity, EntityDataAccessor<Float> acc) {
        try {
            float cur = entity.getEntityData().get(acc);
            if (cur < 0) return false;
            float before = entity.getHealth();
            boolean wrote = DirectHealthFallback.setFloatChannelValue(entity, acc, cur - 1.0f, false);
            float after = entity.getHealth();
            DirectHealthFallback.setFloatChannelValue(entity, acc, cur, false); // 还原
            return wrote && before > 0 && Math.abs((before - after) - 1.0f) < 0.6f;
        } catch (Exception e) {
            return false;
        }
    }

    /** 反查 accessor 实例来自类层级哪个 static 字段（identity 匹配）。 */
    private static String findAccessorFieldName(LivingEntity entity, EntityDataAccessor<?> acc) {
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(null) == acc) return f.getName();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Float> resolveAccessor(HealthSlot slot) {
        try {
            Class<?> c = Class.forName(slot.className());
            for (Class<?> cl = c; cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                try {
                    Field f = cl.getDeclaredField(slot.fieldName());
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof EntityDataAccessor<?> acc) {
                        return (EntityDataAccessor<Float>) acc;
                    }
                } catch (NoSuchFieldException ignored) {}
            }
        } catch (ClassNotFoundException | IllegalAccessException ignored) {}
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

    // ==================== 字符串数值解析 ====================

    /**
     * 从字符串解析数值：支持「前缀+数值」（如 {@code HEC0.0}）与「数值+后缀」（如 {@code 12HP}）。
     * 返回 {@code {值, 数值起点, 数值终点}}；非数值返回 null。
     */
    static double[] parseNumberFromString(String s) {
        if (s == null || s.isEmpty()) return null;
        int n = s.length();
        int start = -1;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                start = i;
                break;
            }
            if ((c == '-' || c == '+') && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        int end = start;
        while (end < n) {
            char c = s.charAt(end);
            if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') end++;
            else break;
        }
        if (end == start) return null;
        String num = s.substring(start, end);
        boolean hasDigit = false;
        for (char c : num.toCharArray()) {
            if (Character.isDigit(c)) {
                hasDigit = true;
                break;
            }
        }
        if (!hasDigit) return null;
        try {
            double v = Double.parseDouble(num);
            if (!Double.isFinite(v)) return null;
            return new double[]{v, start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 按原字符串风格重建（保留前缀/后缀），数值替换为新值。 */
    static String rebuildStringNumber(String original, double[] parsed, double newValue) {
        String prefix = original.substring(0, (int) parsed[1]);
        String suffix = original.substring((int) parsed[2]);
        return prefix + trimNumber(newValue) + suffix;
    }

    private static String trimNumber(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // ==================== 累加器配对 ====================

    /**
     * 找主字段的等值镜像字段（同声明类、同数值类型族、当前值相等），用于累加器
     * （encAcc/lastAcc、accumulatedDamage/lastAccumulatedDamage 类）直写时同步。
     * 多个等值字段 → 不配对（防误伤）。按 (类, 字段) 缓存。
     */
    private static String findMirrorPairField(LivingEntity entity, Field main) {
        String key = main.getDeclaringClass().getName() + "#" + main.getName();
        String cached = MIRROR_CACHE.get(key);
        if (cached != null) return cached.isEmpty() ? null : cached;
        if (MIRROR_NEGATIVE.contains(key)) return null;
        try {
            double v0 = readField(main, entity);
            String found = null;
            for (Field f : main.getDeclaringClass().getDeclaredFields()) {
                if (f == main || f.isSynthetic()) continue;
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) continue;
                Class<?> t = f.getType();
                // 仅 float/double：int/long 计数器（cooldown/tick 类）常与累加器同为 0，会误伤配对
                if (t != float.class && t != double.class) continue;
                try {
                    f.setAccessible(true);
                    double v = readField(f, entity);
                    if (Double.compare(v, v0) == 0) {
                        if (found != null) {
                            found = null; // 多个等值字段 → 不配对
                            break;
                        }
                        found = f.getName();
                    }
                } catch (Exception ignored) {}
            }
            MIRROR_CACHE.put(key, found == null ? "" : found);
            if (found == null) MIRROR_NEGATIVE.add(key);
            return found;
        } catch (Throwable t) {
            MIRROR_NEGATIVE.add(key);
            return null;
        }
    }

    /** 主字段 + 镜像字段同步写（失败静默，由调用方验证兜底）。 */
    private static void writeFieldWithMirror(LivingEntity entity, Field f, double value, String pair) {
        try {
            writeField(f, entity, value);
        } catch (Exception ignored) {}
        if (pair != null && !pair.isEmpty()) {
            try {
                Field pf = findFieldInHierarchy(f.getDeclaringClass().getName(), pair);
                if (pf != null) writeField(pf, entity, value);
            } catch (Exception ignored) {}
        }
    }

    /** 读取槽 meta 中指定键的值。 */
    private static String metaGet(String meta, String key) {
        if (meta == null || meta.isEmpty()) return null;
        for (String part : meta.split(";")) {
            int i = part.indexOf('=');
            if (i > 0 && part.substring(0, i).equals(key)) return part.substring(i + 1);
        }
        return null;
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

    /** 按引用值的类型把数值强制转换（与 DirectHealthFallback.coerceNumber 同款语义）。 */
    private static Number coerceNumber(double d, Object ref) {
        if (ref instanceof Integer) return (int) Math.round(d);
        if (ref instanceof Long) return (long) Math.round(d);
        if (ref instanceof Double) return d;
        if (ref instanceof Short) return (short) Math.round(d);
        if (ref instanceof Byte) return (byte) Math.round(d);
        return (float) d;
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
                String meta = o.has("meta") ? o.get("meta").getAsString() : "";
                CACHE.put(key, new HealthSlot(
                    o.get("class").getAsString(),
                    o.get("field").getAsString(),
                    o.get("type").getAsString(),
                    o.has("inverse") && o.get("inverse").getAsBoolean(),
                    kind,
                    meta));
            }
        } catch (Exception ignored) {}
    }

    public static void save() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            Files.createDirectories(p.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("_version", 2);
            JsonObject slots = new JsonObject();
            CACHE.forEach((k, s) -> {
                JsonObject o = new JsonObject();
                o.addProperty("class", s.className());
                o.addProperty("field", s.fieldName());
                o.addProperty("type", s.type());
                o.addProperty("inverse", s.inverse());
                o.addProperty("kind", s.kind() == null ? "field" : s.kind());
                o.addProperty("meta", s.meta() == null ? "" : s.meta());
                slots.add(k, o);
            });
            root.add("slots", slots);
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ignored) {}
    }
}
