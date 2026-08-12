package net.minecraft.client.yiz.tool.attribute;

import net.minecraft.client.yiz.tizMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.registries.RegistryObject;

import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * 受保护实体属性维护门禁 —— 给实体分配「受保护」属性值的统一入口（1.20.1 移植版）。
 *
 * <p>与 1.21.1 版本同逻辑：给任意 {@link LivingEntity} 挂/改/移除 yizmodqzk 自定义属性值，
 * 使用专属 {@code prot_} 前缀 modifier（确定性 UUID 由 idKey 派生），配合
 * {@link net.minecraft.client.yiz.mixin.AttributeInstanceMixin} 对移除做调用栈鉴权。</p>
 *
 * <p> 1.20.1 差异：{@link AttributeModifier} 构造器 id 参数是 {@link UUID}（非 ResourceLocation），
 * 用 {@link UUID#nameUUIDFromBytes} 派生确定性 UUID 保证 remove 幂等。</p>
 */
public final class EntityAttributeGate {

    private EntityAttributeGate() {}

    /** 受保护 modifier 前缀。完整 modifier id（UUID）由 idKey 派生。 */
    public static final String PROTECTED_PREFIX = "prot_";

    /** 本家包前缀：前置库 + 所有下游共用此包根，形成信任边界。 */
    private static final String FAMILY_PACKAGE = "net.minecraft.client.yiz";

    /** 引擎帧前缀（原版 / Forge / Mojang 库）。 */
    private static final String[] ENGINE_PREFIXES = {
        "net.minecraft.",
        "net.minecraftforge.",
        "com.mojang.",
    };

    /** 被拦截目标类（mixin 注入到 AttributeInstance，必须跳过它的帧）。 */
    private static final String TARGET_CLASS = "net.minecraft.world.entity.ai.attributes.AttributeInstance";

    /** 受信任 modid 白名单。 */
    private static final Set<String> TRUSTED_MODIDS = ConcurrentHashMap.newKeySet();
    static {
        TRUSTED_MODIDS.add("yizmodqzk");
        TRUSTED_MODIDS.add("yizxianmod");
    }

    /** 扩展受信任 modid。 */
    public static void addTrustedModId(String modId) {
        TRUSTED_MODIDS.add(modId);
    }

    /** 由 idKey 派生确定性 UUID（prot_ 前缀）。 */
    public static UUID protectedUuid(String idKey) {
        return UUID.nameUUIDFromBytes(("yizmodqzk:prot_" + idKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 已分配的受保护 modifier UUID 集合（EntityAttributeGate.set 记录，AttributeInstanceMixin 据此判定）。 */
    private static final Set<UUID> PROTECTED_UUIDS = ConcurrentHashMap.newKeySet();

    /** 记录受保护 UUID（set 写入时调用）。 */
    private static void registerProtectedUuid(UUID id) {
        if (id != null) PROTECTED_UUIDS.add(id);
    }

    /** 判断 modifier UUID 是否受保护（由 EntityAttributeGate 分配过）。 */
    public static boolean isProtectedUuid(UUID id) {
        return id != null && PROTECTED_UUIDS.contains(id);
    }

    /**
     * 受保护写入：给实体挂/改某属性值。鉴权后 remove + addPermanentModifier。
     * value = 0 时仅移除。属性未挂在实体 AttributeSupplier 上时静默跳过。
     */
    public static void set(LivingEntity entity, RegistryObject<Attribute> attr, String idKey, double value) {
        if (attr == null || !attr.isPresent()) return;
        AttributeInstance inst = entity != null ? entity.getAttribute(attr.get()) : null;
        if (inst == null) return;
        if (!isCallerTrusted()) {
            tizMod.LOGGER.warn("[AttributeGate] 拒绝非受信任调用方写入受保护属性: {} idKey={}", attr.get().getDescriptionId(), idKey);
            return;
        }
        UUID id = protectedUuid(idKey);
        registerProtectedUuid(id);
        inst.removeModifier(id);
        if (value != 0.0) {
            inst.addPermanentModifier(new AttributeModifier(id, "yizmodqzk:prot_" + idKey, value, AttributeModifier.Operation.ADDITION));
        }
        // 受信任写入的限伤属性权威值同步（防外部直接改属性绕过传导限伤）：编辑器/辖界者 setAttr 均汇聚于此。
        // 用注册表 key 比较而非实例 ==（RegistryObject.create(...).get() 的引用可能与注册表单例不同，导致 vault 不同步）
        Attribute a = attr.get();
        var rl = a != null ? net.minecraftforge.registries.ForgeRegistries.ATTRIBUTES.getKey(a) : null;
        if (rl != null && rl.equals(new net.minecraft.resources.ResourceLocation("yizmodqzk", "conduction_cap"))) {
            net.minecraft.client.yiz.tool.health.ConductionCapVault.register(entity, (float) value);
        }
    }

    /** 受保护移除。 */
    public static void remove(LivingEntity entity, RegistryObject<Attribute> attr, String idKey) {
        if (entity == null) return;
        if (!isCallerTrusted()) {
            tizMod.LOGGER.warn("[AttributeGate] 拒绝非受信任调用方移除受保护属性: idKey={}", idKey);
            return;
        }
        if (attr != null && attr.isPresent()) {
            AttributeInstance inst = entity.getAttribute(attr.get());
            if (inst != null) inst.removeModifier(protectedUuid(idKey));
        }
    }

    /**
     * 调用栈 + 包名鉴权：只看「第一个决定性调用者」。
     */
    public static boolean isCallerTrusted() {
        try {
            java.util.concurrent.atomic.AtomicReference<Boolean> verdict = new java.util.concurrent.atomic.AtomicReference<>();
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> {
                frames.forEach(frame -> {
                    if (verdict.get() != null) return;
                    Class<?> clazz = frame.getDeclaringClass();
                    if (!isDecisiveFrame(clazz)) return;
                    verdict.set(isTrustedFrame(clazz, frame.getMethodName()));
                });
                return null;
            });
            return verdict.get() == null || verdict.get();
        } catch (Exception e) {
            tizMod.LOGGER.warn("[AttributeGate] 调用栈鉴权异常，默认拒绝", e);
            return false;
        }
    }

    private static boolean isDecisiveFrame(Class<?> clazz) {
        String pkg = clazz.getPackageName();
        if (pkg.equals(EntityAttributeGate.class.getPackageName())) return false;
        if (pkg.startsWith("net.minecraft.client.yiz.mixin")) return false;
        return !clazz.getName().equals(TARGET_CLASS);
    }

    private static boolean isTrustedFrame(Class<?> clazz, String methodName) {
        String pkg = clazz.getPackageName();
        if (pkg.startsWith(FAMILY_PACKAGE)) return true;
        if (isEngineFrame(pkg)) {
            return !isExternalMixinFrame(clazz, methodName);
        }
        for (Annotation ann : clazz.getAnnotations()) {
            if (ann.annotationType().getName().equals("net.minecraftforge.fml.common.Mod")) {
                try {
                    String modid = (String) ann.annotationType().getMethod("value").invoke(ann);
                    if (TRUSTED_MODIDS.contains(modid)) return true;
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static boolean isExternalMixinFrame(Class<?> clazz, String methodName) {
        try {
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                var anno = m.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
                if (anno != null) {
                    return !anno.mixin().startsWith(FAMILY_PACKAGE);
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isEngineFrame(String pkg) {
        for (String p : ENGINE_PREFIXES) {
            if (pkg.startsWith(p)) return true;
        }
        return false;
    }
}
