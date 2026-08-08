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
        LOGGER.info("使用 HotSpotVirtualMachine.ALLOW_ATTACH_SELF 策略绕过 JDK 17 Self-Attach 限制...");

        Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) theUnsafeField.get(null);

        List<AttachProvider> providers = AttachProvider.providers();
        if (providers.isEmpty()) {
            throw new IllegalStateException("当前 JVM 未发现任何可用的 AttachProvider!");
        }
        ClassLoader providerLoader = providers.get(0).getClass().getClassLoader();

        Class<?> hotSpotVmClass = Class.forName("sun.tools.attach.HotSpotVirtualMachine", false, providerLoader);
        Field allowAttachSelfField = hotSpotVmClass.getDeclaredField("ALLOW_ATTACH_SELF");
        Object base = unsafe.staticFieldBase(allowAttachSelfField);
        long offset = unsafe.staticFieldOffset(allowAttachSelfField);
        unsafe.putBoolean(base, offset, true);
        LOGGER.info("成功修改 ALLOW_ATTACH_SELF 为 true");

        Class<?> vmPublicClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        Method attachMethod = vmPublicClass.getMethod("attach", String.class);
        Object vmInstance = attachMethod.invoke(null, pid);

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
