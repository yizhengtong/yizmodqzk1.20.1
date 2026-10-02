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
 * Agent 加载器抽象基类（参考 yiz-main 同进程 self-attach 方案）。
 * 封装不变点：提取 Agent JAR、获取 PID、清理临时文件；子类实现附加策略。
 *
 * <h3>备注：开发环境（runClient）必然降级 —— 属预期，不是回归</h3>
 * <p>FG userdev 走的是<b>运行时 self-attach</b>：attach 发生在游戏类路径已就绪之后，此时主 mod jar 里的
 * 同名类 {@code net.minecraft.client.yiz.agent.LivingHealthTransformer}（源码在
 * {@code src/main/java/...}，是 agent 侧那份的精简副本、<b>没有</b> {@code BRIDGE_CLASS_REF} 字段）
 * 已经占据 app classpath，会遮蔽 Agent JAR 内那份（{@code agent/src/...}，<b>有</b>该字段），
 * 于是 attach 抛 {@code NoSuchFieldError: BRIDGE_CLASS_REF} → {@code AgentInitializationException}
 * → 走到 {@link #handleDegradedMode}。</p>
 * <p>生产环境用清单里的 {@code Launcher-Agent-Class}/{@code Premain-Class} 在启动早期加载，
 * 不存在这个遮蔽，因此不降级。<b>所以 dev 日志里的「Agent 加载失败 / 进入降级模式」属正常现象</b>，
 * 要判断实际能力请用 {@code /yiz agent}（{@code YizAgentCommand}），不要用这条日志。</p>
 * <p>若以后想让 dev 也用上 agent：删掉主源码集里的那份冗余副本即可 —— 本类不引用它，
 * agent 只从 JAR 内 {@code META-INF/jarjar/yizmodqzk-agent.jar} 提取后 attach。</p>
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
            // 开发环境会走到这里（见类注释「开发环境必然降级」），排查前先确认是不是 dev
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
        // 开发环境走这里属预期（self-attach 被主 jar 同名类遮蔽，见类注释）；生产环境走这里才是异常
        LOGGER.error("进入降级模式：Agent 干预不可用，系统将使用运行时反射扫描模式（开发环境属预期）");
    }

    @Override
    public boolean isLoaded() {
        return loaded;
    }
}
