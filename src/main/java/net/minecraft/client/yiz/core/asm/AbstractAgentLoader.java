package net.minecraft.client.yiz.core.asm;

import net.minecraft.client.yiz.tizMod;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;

/**
 * Agent 加载器抽象基类（1.20.1 移植版，参考 yiz-main 同进程 self-attach 方案）。
 * 封装不变点：提取 Agent JAR、获取 PID、清理临时文件；子类实现附加策略。
 */
public abstract class AbstractAgentLoader implements IAgentLoader {

    private static final Logger LOGGER = tizMod.LOGGER;

    protected volatile boolean loaded = false;

    protected abstract String getAgentResourcePath();

    @Override
    public final void loadAgent() {
        if (loaded) {
            LOGGER.info("Agent 已加载，跳过重复初始化");
            return;
        }
        File agentJar = null;
        try {
            agentJar = extractAgentJar();
            String pid = getProcessId();
            LOGGER.info("当前进程 PID: {}", pid);
            prepareAndAttach(pid, agentJar.getAbsolutePath());
            loaded = true;
            LOGGER.info("成功注入字节码干预代理");
        } catch (Exception e) {
            LOGGER.error("Agent 加载失败: {}", e.getMessage(), e);
            handleDegradedMode(e);
        } finally {
            if (agentJar != null && agentJar.exists()) {
                agentJar.deleteOnExit();
            }
        }
    }

    private File extractAgentJar() throws IOException {
        String resourcePath = getAgentResourcePath();
        InputStream is = getClass().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("未找到内置 Agent 资源: " + resourcePath);
        }
        File tempAgent = Files.createTempFile("yiz_health_agent", ".jar").toFile();
        tempAgent.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tempAgent)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        }
        is.close();
        return tempAgent;
    }

    private String getProcessId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name.split("@")[0];
    }

    protected abstract void prepareAndAttach(String pid, String agentPath) throws Exception;

    protected void handleDegradedMode(Exception error) {
        LOGGER.error("进入降级模式：Agent 干预不可用，系统将使用运行时反射扫描模式");
    }

    @Override
    public boolean isLoaded() {
        return loaded;
    }
}
