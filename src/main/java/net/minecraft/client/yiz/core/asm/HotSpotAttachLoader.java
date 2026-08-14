package net.minecraft.client.yiz.core.asm;

import com.sun.tools.attach.spi.AttachProvider;
import net.minecraft.client.yiz.tizMod;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * HotSpot 同进程 self-attach 加载器（1.20.1 移植版，参考 yiz-main）。
 *
 * <p>通过修改 {@code HotSpotVirtualMachine.ALLOW_ATTACH_SELF} 静态字段绕过 JDK 17
 * 的 Self-Attach 限制（JEP 403），借用 AttachProvider 类加载器加载 {@code sun.*} 类，
 * 全程反射调用 VirtualMachine API。同进程加载，无需子进程。</p>
 */
public class HotSpotAttachLoader extends AbstractAgentLoader {

    private static final Logger LOGGER = tizMod.LOGGER;
    private static final String AGENT_RESOURCE_PATH = "/META-INF/jarjar/yizmodqzk-agent.jar";

    @Override
    protected String getAgentResourcePath() {
        return AGENT_RESOURCE_PATH;
    }

    @Override
    protected void prepareAndAttach(String pid, String agentPath) throws Exception {
        LOGGER.info("修改 jdk.attach.allowAttachSelf 并 attach + loadAgent 动态加载 Agent...");

        Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) theUnsafeField.get(null);

        // 1. 修改 jdk.internal.misc.VM.savedProps 的 jdk.attach.allowAttachSelf=true。
        //    HotSpotVirtualMachine.ALLOW_ATTACH_SELF 是 final static，<clinit> 从 savedProps 初始化；
        //    直接 Unsafe 改 final 字段会被 <clinit> 重置 + JIT 折叠失效。改 savedProps 后触发 <clinit> 才生效。
        try {
            Class<?> vmClass = Class.forName("jdk.internal.misc.VM");
            Field savedPropsField = vmClass.getDeclaredField("savedProps");
            long offset = unsafe.staticFieldOffset(savedPropsField);
            Object base = unsafe.staticFieldBase(savedPropsField);
            Object propsObj = unsafe.getObject(base, offset);
            // savedProps 可能是 Properties 或 HashMap，统一用 put（Map 接口，原始类型规避通配符）
            if (propsObj instanceof java.util.Map) {
                ((java.util.Map) propsObj).put("jdk.attach.allowAttachSelf", "true");
                LOGGER.info("成功设置 VM.savedProps[jdk.attach.allowAttachSelf]=true");
            } else {
                LOGGER.warn("VM.savedProps 类型异常: {}", propsObj == null ? "null" : propsObj.getClass());
            }
        } catch (Exception e) {
            LOGGER.warn("修改 VM.savedProps 失败: {}", e.getMessage());
        }

        // 2. 触发 HotSpotVirtualMachine.<clinit>（initialize=true）读取 savedProps → ALLOW_ATTACH_SELF=true
        List<AttachProvider> providers = AttachProvider.providers();
        if (providers.isEmpty()) {
            throw new IllegalStateException("当前 JVM 未发现任何可用的 AttachProvider!");
        }
        ClassLoader providerLoader = providers.get(0).getClass().getClassLoader();
        Class.forName("sun.tools.attach.HotSpotVirtualMachine", true, providerLoader);
        LOGGER.info("HotSpotVirtualMachine.<clinit> 已执行（ALLOW_ATTACH_SELF=true）");

        // 3. attach + loadAgent + detach（标准动态加载，Agent-Class + agentmain）
        Class<?> vmPublicClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Method attachMethod = vmPublicClass.getMethod("attach", String.class);
        Object vmInstance = attachMethod.invoke(null, pid);
        if (vmInstance == null) {
            // attach 返回 null：ALLOW_ATTACH_SELF 已在 savedProps 修改前被第三方触发
            // HotSpotVirtualMachine.<clinit> 定型为 false，self-attach 被拒。此处显式抛错，
            // 避免后续 loadAgent/detach 的 invoke(null,...) 抛晦涩 NPE。
            throw new IllegalStateException(
                "self-attach 被拒（attach 返回 null）：ALLOW_ATTACH_SELF 已被提前定型为 false，"
                + "通常因第三方模组提前触发了 HotSpotVirtualMachine.<clinit>");
        }

        try {
            Method loadAgentMethod = vmPublicClass.getMethod("loadAgent", String.class);
            loadAgentMethod.invoke(vmInstance, agentPath);
            LOGGER.info("Agent JAR 加载成功: {}", agentPath);
        } finally {
            Method detachMethod = vmPublicClass.getMethod("detach");
            detachMethod.invoke(vmInstance);
        }
    }
}
