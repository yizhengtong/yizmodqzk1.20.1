package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.tool.attribute.EntityAttributeGate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 真实血量外部存储 — 1.20.1 混淆串方案（2026-08-11 重构）。
 *
 * <p><b>不再用静态 Map/隐藏类/XOR</b>——真实血量存在<b>实体自身的混淆 String DataParameter</b>
 * （{@link HealthChannels#SECURE_OBF} = FloatObf.enc(health, key)，key 是每实体随机 INT
 * {@link HealthChannels#SECURE_OBF_KEY}，随实体存档 + 同步客户端）。</p>
 *
 * <p><b>为什么这样</b>：
 * <ol>
 *   <li><b>无全局共享表</b>：血量在实体身上，单机 client 无法污染服务端（此前"隐藏扣表"根因
 *       就是 client 写共享静态 Map）；</li>
 *   <li><b>行为定位器免疫</b>：存储是 String 非 float/Map，外部设 testValue 看 getHealth 跟随
 *       → 对混淆串无效（dec 失败/错值）；</li>
 *   <li><b>确定性 per-key</b>：同实体读写同 key（随存档/同步），无 noise 不匹配垃圾值
 *       （此前 per-entity 随机 noise 存 Map 的坑）；</li>
 *   <li><b>写入口调用栈鉴权</b>：setHealth 只允许 yiz 家族/引擎帧/白名单调用。</li>
 * </ol></p>
 *
 * <p> 1.20.1 网络差异：String/INT DataParameter 由原版 SynchedEntityData 自动同步客户端，
 * 客户端 getHealth 读混淆串 dec 显示。</p>
 */
public final class SecureHealthClosure {

    private SecureHealthClosure() {}

    /** 是否受保护（服务端）：非客户端 && 有混淆血量存储。客户端一律 false（不参与写/传导链）。 */
    public static boolean isSecure(LivingEntity entity) {
        return entity != null && !entity.level().isClientSide() && hasObf(entity);
    }

    /** 是否混淆血量存储（含客户端显示用）：实体自身挂载了 SECURE_OBF 混淆串 DataItem。
     *   不再用 SECURE_PULSE 属性判定：生产环境发现 SECURE_PULSE 可被外部清零 → hasObf=false →
     *  getHealth 回退 getMaxHealth（被外部 agent 注入压负，血量显示/伤害闸门失效）。
     *  改以"是否有混淆串存储"为准（实体自身 DataItem 无法被伪造为不存在），普通实体未 define 自动 false。 */
    public static boolean hasObf(LivingEntity entity) {
        return hasObfStorage(entity);
    }

    /** 实体是否挂载 SECURE_OBF 混淆串 DataItem（YizxianMob.defineSynchedData 定义，客户端同步）。 */
    public static boolean hasObfStorage(LivingEntity entity) {
        if (entity == null) return false;
        try {
            return entity.getEntityData().hasItem(HealthChannels.getSecureObf());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 兼容旧 API：isRegistered == hasObf（受保护实体即"注册"了混淆存储）。 */
    public static boolean isRegistered(LivingEntity entity) {
        return hasObf(entity);
    }

    // ==================== 表值完整性校验（防外部 DataItem 直写混淆串）====================

    /** 服务端最近合法表值（enforce 每 tick 更新）。 */
    private static final java.util.Map<java.util.UUID, Float> LAST_VALID_TABLE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 表值完整性校验（免改最终兜底）：外部 DataItem 直写混淆串（绕过 D1 set 拦截/D2 钩子/传导 cap）
     * 导致<b>单次掉血超过 maxCap</b> → 判定外部直写，回滚到最近合法表值。
     * 由 enforceSecureHealthState 每 tick 调用；maxCap = 单次传导上限（conductionCap）。
     * 合法传导扣血（≤cap）正常更新最近值；表值=0（正常死亡）清理记录。
     */
    public static void enforceTableIntegrity(LivingEntity entity, float maxCap) {
        if (entity == null || entity.level().isClientSide()) return;
        if (!hasObf(entity)) return;
        float cur = getHealth(entity);
        java.util.UUID id = entity.getUUID();
        if (cur <= 0) {
            removeIntegrity(id);
            return;
        }
        Float last = LAST_VALID_TABLE.get(id);
        if (last == null) {
            LAST_VALID_TABLE.put(id, cur);
            return;
        }
        if (maxCap <= 0) {
            LAST_VALID_TABLE.put(id, cur);
            return;
        }
        // 单次掉血超过 cap → 外部直写大额 → 回滚（不更新 last，外部注入 无法持续推进）
        if (cur < last - maxCap) {
            if (TAB_JUMP_LOG.incrementAndGet() <= 20) {
                net.minecraft.client.yiz.tizMod.LOGGER.warn("[SecureHealthClosure] 表值跳变 {} -> {} (uuid={} cap={}) 回滚",
                    last, cur, id, maxCap);
            }
            beginObfWrite();
            try {
                int key = entity.getEntityData().get(HealthChannels.getSecureObfKey());
                entity.getEntityData().set(HealthChannels.getSecureObf(), FloatObf.enc(last, key));
            } finally {
                endObfWrite();
            }
        } else {
            LAST_VALID_TABLE.put(id, cur);
        }
    }

    /** 清理完整性记录（实体正常死亡/移除时调用）。 */
    public static void removeIntegrity(java.util.UUID id) {
        if (id != null) LAST_VALID_TABLE.remove(id);
    }

    /** 诊断：setHealth 写表调用方（限频前 20 次），定位 外部注入 是否直调 setHealth（绕过鉴权？）。 */
    private static final java.util.concurrent.atomic.AtomicInteger SET_CALL_LOG = new java.util.concurrent.atomic.AtomicInteger();
    private static void logSetCall(LivingEntity entity, float value) {
        if (SET_CALL_LOG.incrementAndGet() > 20) return;
        try {
            StringBuilder sb = new StringBuilder();
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(st.length, 14); i++) sb.append("\n    ").append(st[i]);
            net.minecraft.client.yiz.tizMod.LOGGER.warn("[SecureHealthClosure] setHealth({}) uuid={}:{}",
                value, entity.getUUID(), sb);
        } catch (Throwable ignored) {}
    }

    /** 诊断：表值跳变打印（限频），定位 外部注入 反射直写权威表。 */
    private static final java.util.concurrent.atomic.AtomicInteger TAB_JUMP_LOG = new java.util.concurrent.atomic.AtomicInteger();

    // ==================== 读 ====================

    /** 服务端权威血量表（免改核心，调用栈鉴权容器）：
     *  逻辑血量唯一权威来源。外部反射直写表（put/remove）会被 {@link ProtectedHealthMap} 的
     *  调用栈+门禁鉴权拦截（外部注入 反射/IMPL_LOOKUP 直写失效）。混淆串只是客户端同步镜像，
     *  enforce 每 tick 用表值覆盖。仅服务端维护。 */
    private static final ProtectedHealthMap AUTHORITY_TABLE = new ProtectedHealthMap();

    /** 受保护最大生命值权威表（独立于 vanilla MAX_HEALTH 属性）：
     *  /attribute ... max_health base set 会直改 vanilla MAX_HEALTH 属性，若 getMaxHealth 直接读属性
     *  会被篡改（进而 setHealth 的 clamp 把真实血量压到 1）。权威值 = 模板值 × 难度乘数，
     *  由 applyVanillaDifficultyScale 经 setMaxHealth 记录；外部改属性不影响逻辑上限。 */
    private static final java.util.Map<java.util.UUID, Float> AUTHORITATIVE_MAX = new java.util.concurrent.ConcurrentHashMap<>();

    /** 诊断：外部反射写权威表（限频前 20 次），打印调用栈定位 外部注入 用哪个方法绕过。 */
    private static final java.util.concurrent.atomic.AtomicInteger TABLE_REJECT_LOG = new java.util.concurrent.atomic.AtomicInteger();

    /** 权威表保护容器：<b>所有写方法</b>（put/putIfAbsent/putAll/compute/computeIfAbsent/computeIfPresent/
     *  merge/replace/replaceAll/remove/clear）调用栈鉴权——合法写（setHealth/register/remove 家族调用）
     *  放行；外部反射/IMPL_LOOKUP 直写（含 putIfAbsent/compute 等绕过）被拒。 */
    static final class ProtectedHealthMap extends java.util.concurrent.ConcurrentHashMap<java.util.UUID, Float> {
        @Override
        public Float put(java.util.UUID key, Float value) {
            if (!isTrustedTableWrite()) { logTableReject("put", key, value); return get(key); }
            return super.put(key, value);
        }
        @Override
        public Float putIfAbsent(java.util.UUID key, Float value) {
            if (!isTrustedTableWrite()) { logTableReject("putIfAbsent", key, value); return get(key); }
            return super.putIfAbsent(key, value);
        }
        @Override
        public void putAll(java.util.Map<? extends java.util.UUID, ? extends Float> m) {
            if (!isTrustedTableWrite()) { logTableReject("putAll", null, null); return; }
            super.putAll(m);
        }
        @Override
        public Float compute(java.util.UUID key, java.util.function.BiFunction<? super java.util.UUID, ? super Float, ? extends Float> f) {
            if (!isTrustedTableWrite()) { logTableReject("compute", key, null); return get(key); }
            return super.compute(key, f);
        }
        @Override
        public Float computeIfAbsent(java.util.UUID key, java.util.function.Function<? super java.util.UUID, ? extends Float> f) {
            if (!isTrustedTableWrite()) { logTableReject("computeIfAbsent", key, null); return get(key); }
            return super.computeIfAbsent(key, f);
        }
        @Override
        public Float computeIfPresent(java.util.UUID key, java.util.function.BiFunction<? super java.util.UUID, ? super Float, ? extends Float> f) {
            if (!isTrustedTableWrite()) { logTableReject("computeIfPresent", key, null); return get(key); }
            return super.computeIfPresent(key, f);
        }
        @Override
        public Float merge(java.util.UUID key, Float value, java.util.function.BiFunction<? super Float, ? super Float, ? extends Float> f) {
            if (!isTrustedTableWrite()) { logTableReject("merge", key, value); return get(key); }
            return super.merge(key, value, f);
        }
        @Override
        public Float replace(java.util.UUID key, Float value) {
            if (!isTrustedTableWrite()) { logTableReject("replace", key, value); return get(key); }
            return super.replace(key, value);
        }
        @Override
        public boolean replace(java.util.UUID key, Float oldValue, Float newValue) {
            if (!isTrustedTableWrite()) { logTableReject("replace3", key, newValue); return false; }
            return super.replace(key, oldValue, newValue);
        }
        @Override
        public void replaceAll(java.util.function.BiFunction<? super java.util.UUID, ? super Float, ? extends Float> f) {
            if (!isTrustedTableWrite()) { logTableReject("replaceAll", null, null); return; }
            super.replaceAll(f);
        }
        @Override
        public Float remove(Object key) {
            if (!isTrustedTableWrite()) { logTableReject("remove", key, null); return get(key); }
            return super.remove(key);
        }
        @Override
        public boolean remove(Object key, Object value) {
            if (!isTrustedTableWrite()) { logTableReject("remove2", key, value); return false; }
            return super.remove(key, value);
        }
        @Override
        public void clear() {
            if (!isTrustedTableWrite()) { logTableReject("clear", null, null); return; }
            super.clear();
        }
    }

    /** 诊断：外部写权威表被拒（限频），打印调用栈。 */
    private static void logTableReject(String op, Object key, Object value) {
        if (TABLE_REJECT_LOG.incrementAndGet() > 20) return;
        try {
            StringBuilder sb = new StringBuilder();
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(st.length, 14); i++) sb.append("\n    ").append(st[i]);
            net.minecraft.client.yiz.tizMod.LOGGER.warn("[SecureHealthClosure] 外部写权威表被拒 {} key={} value={}:{}",
                op, key, value, sb);
        } catch (Throwable ignored) {}
    }

    /** 权威表写鉴权：写门禁（家族写入口已设）或 调用栈信任（家族包/引擎帧，反射/invoke 帧天然不信任）。 */
    private static boolean isTrustedTableWrite() {
        if (OBF_WRITE_GATE.get()) return true;
        return EntityAttributeGate.isCallerTrusted();
    }

    /**
     * 读取逻辑血量：<b>服务端读权威表</b>（外部注入 直写混淆串不影响逻辑血量）；客户端读混淆串（显示镜像）。
     */
    /** 客户端最近显示值（显示钳制用）：外部注入 直写混淆串同步客户端 → 血条闪掉，钳制忽略跳变。 */
    private static final java.util.Map<java.util.UUID, Float> CLIENT_DISPLAY = new java.util.concurrent.ConcurrentHashMap<>();

    public static float getHealth(LivingEntity entity) {
        if (entity == null) return 0;
        if (entity.level().isClientSide()) {
            // 客户端：读 vanilla DATA_HEALTH（服务端 enforce 每 tick 用权威表覆盖回写，无混淆 key 依赖——
            // 混淆串 dec 依赖 key，key 未同步/被外部改会 dec 出垃圾值导致血条异常）
            try {
                float v;
                if (DirectHealthFallback.VANILLA_HEALTH_ACCESSOR != null) {
                    v = entity.getEntityData().get(DirectHealthFallback.VANILLA_HEALTH_ACCESSOR);
                } else {
                    String enc = entity.getEntityData().get(HealthChannels.getSecureObf());
                    int key = entity.getEntityData().get(HealthChannels.getSecureObfKey());
                    v = FloatObf.dec(enc, key);
                }
                if (Float.isNaN(v) || Float.isInfinite(v)) return 0;
                float maxHp = entity.getMaxHealth();
                if (maxHp <= 0) maxHp = 20.0f;
                Float last = CLIENT_DISPLAY.get(entity.getUUID());
                // 混淆串（服务端表值镜像）——死亡权威判定：明确 0=真死（判死）；>0=活（用表值）；
                // 未知（空串/解码失败/NaN/负哨兵/镜像未同步）→ 不判死，回落 DATA_HEALTH 兜底
                float obf = Float.NaN;
                if (DirectHealthFallback.VANILLA_HEALTH_ACCESSOR != null) {
                    try {
                        obf = FloatObf.dec(entity.getEntityData().get(HealthChannels.getSecureObf()),
                            entity.getEntityData().get(HealthChannels.getSecureObfKey()));
                    } catch (Throwable ignored) { obf = Float.NaN; }
                }
                String br;
                float ret;
                // 巨大值污染（> maxHp）→ 返回上次/满血（外部直写巨大值）。
                if (v > maxHp) {
                    br = "big"; ret = last != null ? last : maxHp;
                }
                // 负值 = 未初始化的哨兵 enc(-1)（生成竞态窗口）→ 按满血显示，避免客户端误判死消失
                else if (v < 0) {
                    br = "neg"; ret = maxHp;
                }
                // 混淆串严格 ==0.0f（服务端真死）→ 判死。
                // FloatObf enc/dec 是模 2^32 对称运算：enc(0,key) 用同 key dec 严格还原 +0.0f（无 float 噪声）。
                // key 不匹配时 dec 出任意垃圾（实测 -8.376171E-5、4.997E-35、1.5324033E22、-7.7034663E30），
                // 永不等于 0.0f（除 -0.0 外；enc(0) 产出 +0.0 无 -0.0）→ 一律不判死，防客户端误判死移除
                // （服务端保留生命但客户端判死）。真死（key 匹配）obf 精确 0 → 判死；key 不匹配的真死
                // 走不判死 → 由 YizxianMob.remove 手动 Destroy 兜底广播清理。哨兵 enc(-1)=-1 不误判。
                else if (!Float.isNaN(obf) && !Float.isInfinite(obf) && obf == 0.0f) {
                    br = "obf0"; ret = 0;
                }
                // 混淆串明确 >0 → 服务端活着，返回表值（DATA_HEALTH 可能滞后）
                else if (!Float.isNaN(obf) && !Float.isInfinite(obf) && obf > 0.01f) {
                    br = "obf+"; ret = Math.min(obf, maxHp);
                }
                // 混淆串未知（镜像未同步竞态）：DATA_HEALTH ≤0.01 保守按上次/满血，不误判死
                else if (v <= 0.01f) {
                    br = "v0?"; ret = last != null ? last : maxHp;
                }
                // 跳变钳制：单次变化 > 50% maxHp → 忽略（外部直写突变污染；真实传导 cap=25%=100 正常更新）
                else if (last != null && Math.abs(v - last) > maxHp * 0.5f) {
                    br = "jump"; ret = last;
                }
                else {
                    br = "ok"; ret = v;
                }
                if (br.equals("obf0")) {
                    CLIENT_DISPLAY.remove(entity.getUUID());
                } else if (br.equals("obf+") || br.equals("ok")) {
                    CLIENT_DISPLAY.put(entity.getUUID(), ret);
                }
                return ret;
            } catch (Throwable t) {
                return entity.getMaxHealth();
            }
        }
        // 服务端：读权威表（外部注入 直写串不影响逻辑血量）
        Float v = AUTHORITY_TABLE.get(entity.getUUID());
        if (v != null) return v;
        // 表未初始化兜底：读串
        try {
            String enc = entity.getEntityData().get(HealthChannels.getSecureObf());
            int key = entity.getEntityData().get(HealthChannels.getSecureObfKey());
            float sv = FloatObf.dec(enc, key);
            if (Float.isNaN(sv) || Float.isInfinite(sv)) return 0;
            if (sv < 0) return entity.getMaxHealth(); // 哨兵 enc(-1) 未初始化 → 按满血（生成竞态窗口不判死）
            return sv;
        } catch (Throwable t) {
            if (hasObf(entity)) {
                logDecFailOnce(entity, t);
                return 0;
            }
            return entity.getMaxHealth();
        }
    }

    /** 注册实体到权威表（服务端；registerSecureHealth 首次调用）。 */
    public static void registerAuthority(LivingEntity entity, float initialHp) {
        if (entity == null || entity.level().isClientSide()) return;
        if (initialHp < 0) initialHp = 0;
        AUTHORITY_TABLE.put(entity.getUUID(), initialHp);
    }

    /** 诊断：外部注入 直写串痕迹（表值 vs 串值不一致），限频前 20 次。 */
    private static final java.util.concurrent.atomic.AtomicInteger DRIFT_LOG = new java.util.concurrent.atomic.AtomicInteger();

    /** 每 tick 用权威表值覆盖混淆串（服务端；enforceSecureHealthState 调）——外部注入 直写串被拉回。 */
    public static void enforceAuthority(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return;
        Float v = AUTHORITY_TABLE.get(entity.getUUID());
        if (v == null) return;
        try {
            int key = entity.getEntityData().get(HealthChannels.getSecureObfKey());
            String enc = entity.getEntityData().get(HealthChannels.getSecureObf());
            float obf = FloatObf.dec(enc, key);
            // 诊断：表值 vs 串值不一致 = 外部注入 直写混淆串痕迹（本 tick 内被改）
            if (!Float.isNaN(obf) && Math.abs(obf - v) > 0.5f && DRIFT_LOG.incrementAndGet() <= 20) {
                net.minecraft.client.yiz.tizMod.LOGGER.warn("[SecureHealthClosure] 表值 {} vs 串值 {}（uuid={} 外部注入直写串，enforce覆盖）",
                    v, obf, entity.getUUID());
            }
            beginObfWrite();
            try {
                entity.getEntityData().set(HealthChannels.getSecureObf(), FloatObf.enc(v, key));
            } finally {
                endObfWrite();
            }
        } catch (Throwable ignored) {}
    }

    /** 清理权威表（实体死亡/移除时）。 */
    public static void removeAuthority(LivingEntity entity) {
        if (entity != null) AUTHORITY_TABLE.remove(entity.getUUID());
    }

    /** 受保护实体 dec 失败诊断（限频前 5 次）：打印 hasObfStorage/key/串/异常/调用栈，定位 old≠current。 */
    private static final java.util.concurrent.atomic.AtomicInteger DEC_FAIL_LOG = new java.util.concurrent.atomic.AtomicInteger();
    private static void logDecFailOnce(LivingEntity entity, Throwable t) {
        if (DEC_FAIL_LOG.incrementAndGet() > 5) return;
        try {
            int k = entity.getEntityData().get(HealthChannels.getSecureObfKey());
            String e = entity.getEntityData().get(HealthChannels.getSecureObf());
            StringBuilder sb = new StringBuilder();
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(st.length, 10); i++) sb.append("\n    ").append(st[i]);
            net.minecraft.client.yiz.tizMod.LOGGER.warn("[SecureHealthClosure] 受保护dec失败 (hasObfStorage={} key={} 串={} err={}):{}",
                hasObfStorage(entity), k, e.length() > 12 ? e.substring(0, 12) : e, t.getClass().getSimpleName(), sb);
        } catch (Throwable ignored) {}
    }

    /** 受保护最大生命值：优先读独立权威表（防 /attribute base set 直改 vanilla 属性穿透），
     *  fallback 读 vanilla MAX_HEALTH 属性。 */
    public static float getMaxHealth(LivingEntity entity) {
        if (entity == null) return 20.0F;
        Float auth = AUTHORITATIVE_MAX.get(entity.getUUID());
        if (auth != null && auth > 0) return auth;
        var inst = entity.getAttribute(Attributes.MAX_HEALTH);
        return inst != null ? (float) inst.getValue() : 20.0F;
    }

    // ==================== 写（调用栈鉴权 + 混淆串写门禁）====================

    /** 写入口鉴权：只允许 yiz 家族包 / 引擎帧 / 本家 modid 调用。 */
    private static boolean requireTrusted(String op) {
        if (EntityAttributeGate.isCallerTrusted()) return true;
        net.minecraft.client.yiz.tizMod.LOGGER.warn(
            "[SecureHealthClosure] 拒绝非受信任调用方执行 {}（外部模组直写血量被拦）", op);
        return false;
    }

    /** 混淆串「写门禁」：数据层（SynchedEntityData.set mixin）据此区分家族写 vs 外部直写。
     *  家族写入口（setHealth/registerSecureHealth/onSyncedDataUpdated 回写）先 beginObfWrite()
     *  再 entityData.set(...)，写完 endObfWrite()。外部无门禁 → 数据层拦截 cancel。 */
    private static final ThreadLocal<Boolean> OBF_WRITE_GATE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void beginObfWrite() { OBF_WRITE_GATE.set(Boolean.TRUE); }
    public static void endObfWrite() { OBF_WRITE_GATE.remove(); }
    public static boolean isObfWriteAllowed() { return OBF_WRITE_GATE.get(); }

    /** 写混淆血量（服务端）。调用栈鉴权；客户端/非受保护不处理。 */
    public static void setHealth(LivingEntity entity, float value) {
        if (entity == null) return;
        if (!hasObf(entity)) return; // 非受保护实体：调用方自行走 vanilla
        if (entity.level().isClientSide()) return; // 客户端不写
        if (!requireTrusted("setHealth")) return;
        if (value < 0) value = 0;
        if (Float.isNaN(value)) value = 0;
        // 不超 maxHp（secure 实体回血/直写不突破上限；必须走权威表 getMaxHealth 而非 entity.getMaxHealth——
        // entity.getMaxHealth() 读 vanilla MAX_HEALTH 属性，/attribute base set 改属性后 clamp 会把血量压到 1）
        try {
            float maxHp = SecureHealthClosure.getMaxHealth(entity);
            if (maxHp > 0 && value > maxHp) value = maxHp;
        } catch (Throwable ignored) {}
        //  临时诊断（排查"玩家打 -100 隐藏扣表"）：大幅扣串打印调用栈 + hasObfStorage/key/串
        try {
            float old = getHealth(entity);
            if (old - value > 5.0f) {
                StringBuilder sb = new StringBuilder();
                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                for (int i = 2; i < Math.min(st.length, 14); i++) sb.append("\n    ").append(st[i]);
                int k = entity.getEntityData().get(HealthChannels.getSecureObfKey());
                String e = entity.getEntityData().get(HealthChannels.getSecureObf());
                net.minecraft.client.yiz.tizMod.LOGGER.warn("[SecureHealthClosure] 大幅扣串[{}] {} -> {} (uuid={} hasObfStorage={} key={} 串={}):{}",
                    Thread.currentThread().getName(), old, value, entity.getUUID(),
                    hasObfStorage(entity), k, e.length() > 12 ? e.substring(0, 12) : e, sb);
            }
        } catch (Throwable ignored) {}
        AUTHORITY_TABLE.put(entity.getUUID(), value);   // 服务端权威表（逻辑血量唯一来源，外部注入 直写串不影响）
        logSetCall(entity, value);   // 诊断：写表调用方（定位 外部注入 是否直调 setHealth）
        try {
            int key = entity.getEntityData().get(HealthChannels.getSecureObfKey());
            beginObfWrite();
            try {
                entity.getEntityData().set(HealthChannels.getSecureObf(), FloatObf.enc(value, key));
            } finally {
                endObfWrite();
            }
        } catch (Throwable ignored) {}
    }

    /** 兼容旧 API：注册（混淆存储在 defineSynchedData 定义，此处 no-op）。 */
    public static void register(LivingEntity entity, float initialHp) {}

    /**
     * 无正负限制写混淆血量（供 /yiz setHealth 指令等改血工具用；NaN 仍归一）。服务端。
     *
     * <p>与 {@link #setHealth} 区别：不对负值 clamp 到 0（命令允许把血量设为负值/零/超大值，
     * 负值即判定死亡、零即死亡、超大值即超高血量）。写入前清 {@code LAST_VALID_TABLE} 完整性记录，
     * 防止 {@link #enforceTableIntegrity} 把命令设置的跳变当「外部直写」回滚。</p>
     */
    public static void setHealthUnbounded(LivingEntity entity, float value) {
        if (entity == null) return;
        if (!hasObf(entity)) return;
        if (entity.level().isClientSide()) return;
        if (!requireTrusted("setHealthUnbounded")) return;
        if (Float.isNaN(value)) value = 0;
        removeIntegrity(entity.getUUID());                          // 清完整性记录（防回滚）
        AUTHORITY_TABLE.put(entity.getUUID(), value);               // 服务端权威表（无 clamp）
        try {
            int key = entity.getEntityData().get(HealthChannels.getSecureObfKey());
            beginObfWrite();
            try {
                entity.getEntityData().set(HealthChannels.getSecureObf(), FloatObf.enc(value, key));
            } finally {
                endObfWrite();
            }
        } catch (Throwable ignored) {}
    }

    /** 记录受保护最大生命值权威值（applyVanillaDifficultyScale 调用）。服务端 + 调用栈鉴权，
     *  外部模组直调被拒。getMaxHealth 优先读此表，防 /attribute base set 改 vanilla 属性穿透。 */
    public static void setMaxHealth(LivingEntity entity, float value) {
        if (entity == null || value <= 0) return;
        if (entity.level().isClientSide()) return;
        if (!requireTrusted("setMaxHealth")) return;
        AUTHORITATIVE_MAX.put(entity.getUUID(), value);
    }

    /** 兼容旧 API：tick（无表可清，no-op）。 */
    public static void tick(LivingEntity entity) {}

    /** 实体移除时清理权威最大生命值表（LivingEntityMixin 移除路径调用）。 */
    public static void removeAll(LivingEntity entity) {
        if (entity == null) return;
        AUTHORITATIVE_MAX.remove(entity.getUUID());
    }
}
