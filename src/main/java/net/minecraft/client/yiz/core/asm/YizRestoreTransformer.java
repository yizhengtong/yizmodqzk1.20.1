package net.minecraft.client.yiz.core.asm;

import net.minecraft.client.yiz.tizMod;
import org.slf4j.Logger;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自我保护还原 transformer（参考 buer/Grae 同思路）。
 *
 * <p>对受保护的本模组类，<b>每次 transform pass 直接返回 jar 里的原始字节</b>，盖掉任何外部注入
 * （coremod/agent 对本模组实体类的字节改写）。1.20.1 实战对象：fantasy(FantasyEndingCore) 的
 * SoftGetHealthClassVisitor 对<b>所有 super 链含 LivingEntity 的类</b>在类加载期改写
 * {@code getHealth()/isAlive()/isDeadOrDying()}——我们 YizxianMob 全中招，运行时字节≠源码。
 * 本 transformer 在 agent 已 attach（晚于外部 coremod）后注册（canRetransform=true）→ transform 链里
 * 本 transformer 最后执行 → 对受保护类返回 jar 字节即终态，外部怎么改都被还原。</p>
 *
 * <p><b>jar 字节来源</b>：transform 传入的 loader（定义类加载器）{@code getResourceAsStream(internal+".class")}
 * 读 jar 打包的原始 class（transformer 只改 define 时字节，不改 jar 资源）。缓存防重复 IO。</p>
 *
 * <p><b>限制</b>：redefine/retransform 不能增删方法/字段/改签名——只适配"外部改方法体"场景（当前 fantasy
 * 正是仅改方法体）。若外部改结构（加方法/字段）retransform 会失败，register 侧逐个降级并记日志。</p>
 *
 * <p><b>线程安全</b>：transform 可能在任意加载线程执行，只引用已加载的 JVM 类与静态日志，不触发加载
 * 本模组其它类（避免 transform 期间递归加载死锁）。</p>
 */
public final class YizRestoreTransformer implements ClassFileTransformer {

    private static final Logger LOGGER = tizMod.LOGGER;

    /** 受保护类 internal 名集合（className 参数为 '/' 分隔 internal 名）。共享 live set，可后续追加。 */
    private final Set<String> protectedNames;
    /** internal 名 → jar 原始字节缓存。 */
    private final Map<String, byte[]> originalCache = new ConcurrentHashMap<>();
    private final AtomicInteger logCount = new AtomicInteger();

    public YizRestoreTransformer(Set<String> protectedNames) {
        this.protectedNames = protectedNames;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (className == null || !protectedNames.contains(className)) return null;
        byte[] orig = originalCache.computeIfAbsent(className, k -> readJarBytes(loader, k));
        if (orig == null) {
            // 读不到 jar 字节（异常）→ 不动，避免破坏类
            return null;
        }
        if (logCount.incrementAndGet() <= 20) {
            LOGGER.warn("[YizRestore] 自保护还原 {} → jar 原始字节 ({}B loader={})",
                className, orig.length, loader);
        }
        return orig;
    }

    /** 从定义类加载器读 jar 原始 class 字节；读不到返回 null。 */
    private byte[] readJarBytes(ClassLoader loader, String internalName) {
        String res = internalName + ".class";
        try {
            ClassLoader cl = loader;
            if (cl == null) cl = YizRestoreTransformer.class.getClassLoader();
            try (InputStream in = cl.getResourceAsStream(res)) {
                if (in != null) return in.readAllBytes();
            }
            // 兜底：线程上下文加载器
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null && ctx != cl) {
                try (InputStream in2 = ctx.getResourceAsStream(res)) {
                    if (in2 != null) return in2.readAllBytes();
                }
            }
        } catch (Throwable t) {
            if (logCount.incrementAndGet() <= 20) {
                LOGGER.error("[YizRestore] 读 jar 原始字节失败 {}: {}", internalName, t.toString());
            }
        }
        return null;
    }
}
