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
    private static final Set<String> SS_CIPHER_DIAG = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicInteger SS_WRITE_DIAG2 = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger SS_STR_SCAN_DIAG = new java.util.concurrent.atomic.AtomicInteger();
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
                case "sscipher": {
                    EncodedValueCodec.Solution sol = effectiveSscipherSolution(slot, entity);
                    if (sol == null) return null;
                    String value = sscipherValue(entity);
                    if (value == null) return null;
                    int[] tok = extractTokenAfterHash(value, entity.getUUID().hashCode());
                    if (tok == null) return null;
                    double d = sol.decode(Float.intBitsToFloat(tok[0]));
                    return Double.isFinite(d) ? d : null;
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
                case "sscipher": {
                    EncodedValueCodec.Solution sol = effectiveSscipherSolution(slot, entity);
                    if (sol == null) return false;
                    // 按 hash 值模式重新定位 accessor，不依赖缓存的 accessor id（每只隐藏类实体的
                    // SS_HEALTH id 可能不同，缓存 id 会在「新召唤的实体」上失效 → 没效果）
                    EntityDataAccessor<?> acc = findSscipherAccessor(entity);
                    if (acc == null) return false;
                    String value = stringValueById(entity, acc.getId());
                    if (value == null) return false;
                    // 每次按当前串 + hash 重新定位加密值位置（不依赖检测时缓存的 tokStart/tokEnd，
                    // 防止目标自己重写串、位数变化后区间错位 → 「有时改得到实际位置有时改不到」）
                    int[] tok = extractTokenAfterHash(value, entity.getUUID().hashCode());
                    if (tok == null) return false;
                    double encoded = sol.encode(logical);
                    if (!Double.isFinite(encoded)) return false;
                    int encInt = Float.floatToRawIntBits((float) encoded);
                    // extractTokenAfterHash 返回 [值, start, end]，rebuildToken 需要 [start, end]
                    String rebuilt = rebuildToken(value, new int[]{tok[1], tok[2]}, Integer.toString(encInt));
                    boolean ok = setStringById(entity, acc.getId(), rebuilt);
                    if (SS_WRITE_DIAG2.incrementAndGet() <= 8) {
                        String after = stringValueById(entity, acc.getId());
                        Double rb = null;
                        if (after != null) {
                            int[] tok2 = extractTokenAfterHash(after, entity.getUUID().hashCode());
                            if (tok2 != null) rb = sol.decode(Float.intBitsToFloat(tok2[0]));
                        }
                        net.minecraft.client.yiz.tizMod.LOGGER.warn("[EHL-SSW2] accId={} 目标={} 写前串={} 编码int={} 重建串={} 写后串={} 回读血={} ok={}",
                            acc.getId(), logical, value, encInt, rebuilt, after, rb, ok);
                    }
                    return ok;
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
            // 字符串通道（前缀/后缀数值编码 / 密钥异或+旋转可逆混淆）
            // 注意：不共享数值通道的 probed 预算（加密藏血实体可能数值通道很多、先耗尽预算，
            // 导致字符串检测被跳过 → 同一类实体「有的测得到有的测不到」）。
            DirectHealthFallback.forEachStringItem(entity, (acc, value, item) -> {
                if (found[0] != null) return;
                // 通用可逆混淆检测：扫描串内每个有符号整数 token，用实体 UUID 哈希作候选密钥
                // 做「密钥异或 + 位旋转」反解（黑箱推断，不依赖前缀/类名/字段名）。
                int hash = entity.getUUID().hashCode();
                java.util.List<int[]> tokens = scanIntTokens(value);
                if (SS_STR_SCAN_DIAG.incrementAndGet() <= 5) {
                    net.minecraft.client.yiz.tizMod.LOGGER.warn("[EHL-STRSCAN] {} value={} hash={} tokens={} maxHp={}",
                        entity.getClass().getName(), value, hash, tokens.size(), maxHp);
                }
                for (int[] tok : tokens) {
                    EncodedValueCodec.Solution sol =
                        net.minecraft.client.yiz.tool.health.codec.BlackBoxInverseSolver.inferKeyedRotation(entity, tok[0], hash);
                    if (sol == null) continue;
                    if (SS_CIPHER_DIAG.add(entity.getClass().getName())) {
                        net.minecraft.client.yiz.tizMod.LOGGER.warn("[EHL-CIPHER] {} value={} 命中token={} {}",
                            entity.getClass().getName(), value, tok[0], sol.toMeta());
                    }
                    String fieldName = findAccessorFieldName(entity, acc);
                    if (fieldName == null) fieldName = "#id:" + acc.getId();
                    String meta = sol.toMeta() + ";tokStart=" + tok[1] + ";tokEnd=" + tok[2];
                    found[0] = new HealthSlot(entity.getClass().getName(), fieldName, "string", false, "sscipher", meta);
                    return;
                }
                // 无分隔符拼接（加密值为正数时哈希与加密值连成一个超长数字，scanIntTokens 会溢出跳过）：
                // 用已知的哈希十进制串在 value 中定位，取其后的有符号整数作加密值。
                int[] htok = extractTokenAfterHash(value, hash);
                if (htok != null) {
                    EncodedValueCodec.Solution sol =
                        net.minecraft.client.yiz.tool.health.codec.BlackBoxInverseSolver.inferKeyedRotation(entity, htok[0], hash);
                    if (sol != null) {
                        String fieldName = findAccessorFieldName(entity, acc);
                        if (fieldName == null) fieldName = "#id:" + acc.getId();
                        String meta = sol.toMeta() + ";tokStart=" + htok[1] + ";tokEnd=" + htok[2];
                        found[0] = new HealthSlot(entity.getClass().getName(), fieldName, "string", false, "sscipher", meta);
                        return;
                    }
                }
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
            // 字段名匹配失败时的兜底：按访问器 id 定位（fieldName 形如 "#id:<n>"）
            if (slot.fieldName() != null && slot.fieldName().startsWith("#id:")) {
                int wantId;
                try {
                    wantId = Integer.parseInt(slot.fieldName().substring(4));
                } catch (NumberFormatException ignored) {
                    return null;
                }
                for (Class<?> cl = c; cl != null && cl != Object.class; cl = cl.getSuperclass()) {
                    for (Field f : cl.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                        try {
                            f.setAccessible(true);
                            Object v = f.get(null);
                            if (v instanceof EntityDataAccessor<?> acc && acc.getId() == wantId) {
                                return (EntityDataAccessor<Float>) acc;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                return null;
            }
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
     * 加密串藏血槽的通用解密/加密（XOR + rotate 可逆变换）。
     *
     * <p>存储格式为 {@code <前缀><hash>-<加密值>}，其中 {@code <hash>} 是实体 UUID 的 hashCode 的
     * 十进制文本，{@code <加密值>} 是对血量做可逆混淆后的有符号十进制。这类实体同时把
     * {@code setHealth} 写成空方法（绕普通改血），真实血量只经此加密串进出。解密只依赖
     * 「字符串尾部的有符号整数」与实体 UUID，不依赖任何具体类名。</p>
     */
    /** 从槽 fieldName（形如 "#id:<n>"）解析访问器 id；失败返回 -1。 */
    private static int accessorIdFromSlot(HealthSlot slot) {
        String fn = slot.fieldName();
        if (fn != null && fn.startsWith("#id:")) {
            try {
                return Integer.parseInt(fn.substring(4));
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    /** 用当前实体的 UUID 哈希重新推导 XOR_ROT 密钥（只复用缓存的旋转量；密钥每实体不同，不能缓存）。 */
    private static EncodedValueCodec.Solution effectiveSscipherSolution(HealthSlot slot, LivingEntity entity) {
        EncodedValueCodec.Solution cached = EncodedValueCodec.Solution.fromMeta(slot.meta());
        if (cached == null) return null;
        int hash = entity.getUUID().hashCode();
        return EncodedValueCodec.Solution.xorRot(hash, -hash, cached.rotation());
    }

    /** 按「串内含 UUID 哈希 + 后跟有符号整数」通用特征找加密藏血槽的访问器（不认前缀/类名/字段名/id）。 */
    private static EntityDataAccessor<?> findSscipherAccessor(LivingEntity entity) {
        int hash = entity.getUUID().hashCode();
        final EntityDataAccessor<?>[] found = {null};
        DirectHealthFallback.forEachStringItem(entity, (acc, value, item) -> {
            if (found[0] == null && extractTokenAfterHash(value, hash) != null) found[0] = acc;
        });
        return found[0];
    }

    /** 读取加密藏血槽的当前字符串值（按「串内含哈希 + 有符号整数」定位，不依赖缓存的 accessor id）。 */
    private static String sscipherValue(LivingEntity entity) {
        int hash = entity.getUUID().hashCode();
        final String[] val = {null};
        DirectHealthFallback.forEachStringItem(entity, (acc, value, item) -> {
            if (val[0] == null && extractTokenAfterHash(value, hash) != null) val[0] = value;
        });
        return val[0];
    }

    /** 按访问器 id 读字符串通道值（遍历实际 DataItem，不依赖反射/类名）。 */
    private static String stringValueById(LivingEntity entity, int accId) {
        final String[] cur = {null};
        DirectHealthFallback.forEachStringItem(entity, (a, v, item) -> {
            if (cur[0] == null && a.getId() == accId) cur[0] = v;
        });
        return cur[0];
    }

    /** 按访问器 id 写字符串通道值。 */
    private static boolean setStringById(LivingEntity entity, int accId, String value) {
        final EntityDataAccessor<?>[] acc = {null};
        DirectHealthFallback.forEachStringItem(entity, (a, v, item) -> {
            if (acc[0] == null && a.getId() == accId) acc[0] = a;
        });
        return acc[0] != null && DirectHealthFallback.setStringChannelValue(entity, acc[0], value, true);
    }

    /** 从槽 meta 解析 token 区间 [start, end)；缺失返回 null。 */
    private static int[] tokenRangeFromMeta(String meta) {
        int start = -1, end = -1;
        if (meta != null) {
            for (String part : meta.split(";")) {
                int i = part.indexOf('=');
                if (i <= 0) continue;
                String k = part.substring(0, i);
                String v = part.substring(i + 1);
                try {
                    if (k.equals("tokStart")) start = Integer.parseInt(v);
                    else if (k.equals("tokEnd")) end = Integer.parseInt(v);
                } catch (NumberFormatException ignored) {}
            }
        }
        return (start >= 0 && end > start) ? new int[]{start, end} : null;
    }

    /** 解析串内 [start, end) 处的有符号整数；失败返回 null。 */
    private static Integer parseIntToken(String value, int[] tok) {
        try {
            if (value == null || tok[0] < 0 || tok[1] > value.length()) return null;
            return Integer.parseInt(value.substring(tok[0], tok[1]).trim());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 用已知哈希十进制串在 value 中定位，取其后的有符号整数（处理无分隔符拼接的情况）。 */
    private static int[] extractTokenAfterHash(String value, int hash) {
        if (value == null) return null;
        String hashStr = Integer.toString(hash);
        int idx = value.indexOf(hashStr);
        if (idx < 0) return null;
        int start = idx + hashStr.length();
        if (start >= value.length()) return null;
        int end = start;
        char c0 = value.charAt(start);
        if (c0 == '-' || c0 == '+') end++;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        if (end <= start || (end == start + 1 && (c0 == '-' || c0 == '+'))) return null;
        try {
            int enc = Integer.parseInt(value.substring(start, end));
            return new int[]{enc, start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 扫描字符串内所有有符号十进制整数 token，返回 {值, 起点, 终点} 列表。 */
    private static java.util.List<int[]> scanIntTokens(String s) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (Character.isDigit(s.charAt(i)) || ((s.charAt(i) == '-' || s.charAt(i) == '+')
                    && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int start = i;
                i++;
                while (i < n && Character.isDigit(s.charAt(i))) i++;
                try {
                    out.add(new int[]{Integer.parseInt(s.substring(start, i)), start, i});
                } catch (NumberFormatException ignored) {}
            } else {
                i++;
            }
        }
        return out;
    }

    /** 用新整数字面量替换串内 [start, end) 区间。 */
    private static String rebuildToken(String value, int[] tok, String newInt) {
        if (value == null || tok[0] < 0 || tok[1] > value.length()) return value;
        return value.substring(0, tok[0]) + newInt + value.substring(tok[1]);
    }

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
     * 直写时同步。多个等值字段 → 不配对（防误伤）。按 (类, 字段) 缓存。
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
