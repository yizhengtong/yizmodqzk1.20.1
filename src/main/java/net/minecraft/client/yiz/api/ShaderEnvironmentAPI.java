package net.minecraft.client.yiz.api;

import java.lang.reflect.Method;

/**
 * 着色器环境检测与兼容性 API — 着色器保护
 *
 * <p>提供 Iris/Oculus 光影模组的检测和兼容性配置，
 * 确保自定义 {@code ShaderInstance} 在光影包激活状态下不会因
 * <b>allowUnknownShaders</b> 限制而被屏蔽。</p>
 *
 * <p>所有操作通过反射实现，对 Iris 无硬依赖。
 * 无论 Iris 是否安装，所有方法均可安全调用。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 在 RegisterShadersEvent 触发前确保兼容
 * if (ShaderEnvironmentAPI.isIrisLoaded()) {
 *     ShaderEnvironmentAPI.ensureShaderCompatibility();
 * }
 * }</pre>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>光影包状态：250ms 缓存（高频查询优化）</li>
 *   <li>allowUnknown 配置：1000ms 缓存</li>
 * </ul>
 */
// 大白话: 光影检测方法
public final class ShaderEnvironmentAPI {

    private ShaderEnvironmentAPI() {}

    // ─────────────────────────────────────────────────────────────────
    //  Iris 反射元信息 — 通过 Class.forName 检测，无硬依赖
    // ─────────────────────────────────────────────────────────────────

    /** Iris 主类（NeoForge/Fabric 通用） */
    private static final String IRIS_MAIN_CLASS = "net.irisshaders.iris.Iris";

    /** Iris API 类名（各版本可能有差异，遍历尝试） */
    private static final String[] IRIS_API_CLASSES = {
            "net.irisshaders.api.IrisApi",
            "net.irisshaders.iris.api.v0.IrisApi"
    };

    // ─────────────────────────────────────────────────────────────────
    //  缓存反射句柄
    // ─────────────────────────────────────────────────────────────────

    private static Method iris_isPackInUseQuick;
    private static Method iris_getIrisConfig;
    private static Method irisConfig_shouldAllowUnknownShaders;
    private static Method irisConfig_setUnknown;

    private static Object irisApiInstance;
    private static Method irisApi_isShaderPackInUse;

    // ─────────────────────────────────────────────────────────────────
    //  查询结果缓存（降低反射调用频率）
    // ─────────────────────────────────────────────────────────────────

    private static long lastPackCheckMs = 0;
    private static boolean packInUseCache = false;

    private static long lastUnknownCheckMs = 0;
    private static boolean allowUnknownCache = false;

    // ─────────────────────────────────────────────────────────────────
    //  公开 API
    // ─────────────────────────────────────────────────────────────────

    /**
     * 检测 Iris 模组是否已加载。
     *
     * @return true = Iris 存在于 classpath
     */
    public static boolean isIrisLoaded() {
        try {
            Class.forName(IRIS_MAIN_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 检测当前是否启用了光影包（Iris shader pack）。
     *
     * <p>优先使用 Iris 内部快速路径 {@code Iris.isPackInUseQuick()}，
     * 回退到 Iris API 的 {@code IrisApi.isShaderPackInUse()}。</p>
     *
     * <p>结果缓存 250ms，避免每帧反射调用开销。</p>
     *
     * @return true = 光影包正在使用中
     */
    public static boolean isShaderPackInUse() {
        long now = System.currentTimeMillis();
        if (now - lastPackCheckMs < 250) {
            return packInUseCache;
        }
        lastPackCheckMs = now;

        // 优先：Iris 内部快速路径
        try {
            initIrisCoreReflection();
            if (iris_isPackInUseQuick != null) {
                packInUseCache = (boolean) iris_isPackInUseQuick.invoke(null);
                return packInUseCache;
            }
        } catch (Throwable ignored) {
            // fallthrough to API
        }

        // 回退：Iris API（版本兼容）
        try {
            initIrisApiReflection();
            if (irisApiInstance != null && irisApi_isShaderPackInUse != null) {
                packInUseCache = (boolean) irisApi_isShaderPackInUse.invoke(irisApiInstance);
            } else {
                packInUseCache = false;
            }
        } catch (Throwable t) {
            packInUseCache = false;
        }
        return packInUseCache;
    }

    /**
     * 查询 Iris 是否允许未知着色器。
     *
     * <p>返回 {@link net.irisshaders.iris.config.IrisConfig#shouldAllowUnknownShaders()}
     * 的值。当此值为 {@code false} 时，非光影包自带的 {@code ShaderInstance} 不会生效。</p>
     *
     * <p>结果缓存 1000ms。</p>
     *
     * @return true = 允许未知着色器
     */
    public static boolean isUnknownShaderAllowed() {
        long now = System.currentTimeMillis();
        if (now - lastUnknownCheckMs < 1000) {
            return allowUnknownCache;
        }
        lastUnknownCheckMs = now;

        try {
            initIrisCoreReflection();
            if (iris_getIrisConfig == null || irisConfig_shouldAllowUnknownShaders == null) {
                allowUnknownCache = false;
                return false;
            }
            Object cfg = iris_getIrisConfig.invoke(null);
            if (cfg == null) {
                allowUnknownCache = false;
                return false;
            }
            allowUnknownCache = (boolean) irisConfig_shouldAllowUnknownShaders.invoke(cfg);
        } catch (Throwable t) {
            allowUnknownCache = false;
        }
        return allowUnknownCache;
    }

    /**
     * 设置 Iris 是否允许未知着色器。
     *
     * <p>当启用时，自定义 {@code ShaderInstance} 可在光影包激活状态下正常使用。
     * 此设置全局生效（影响所有未知着色器），修改后自动保存到 Iris 配置。</p>
     *
     * @param allow true = 允许未知着色器
     */
    public static void setAllowUnknownShaders(boolean allow) {
        try {
            initIrisCoreReflection();
            if (iris_getIrisConfig == null || irisConfig_setUnknown == null) return;

            Object cfg = iris_getIrisConfig.invoke(null);
            if (cfg == null) return;

            irisConfig_setUnknown.invoke(cfg, allow);

            allowUnknownCache = allow;
            lastUnknownCheckMs = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 确保自定义着色器兼容性 — 自动启用 allowUnknownShaders。
     *
     * <p>在注册自定义着色器之前调用此方法，确保 Iris 不会屏蔽它们。
     * 如果 allowUnknown 已启用则无操作，否则自动启用。</p>
     *
     * @return true = 兼容性已确保（或 Iris 未安装），false = 启用失败
     */
    public static boolean ensureShaderCompatibility() {
        if (!isIrisLoaded()) {
            return true; // 没有 Iris 就不需要保护
        }

        if (isUnknownShaderAllowed()) {
            return true; // 已经允许
        }

        setAllowUnknownShaders(true);

        // 验证是否成功
        return isUnknownShaderAllowed();
    }

    // ─────────────────────────────────────────────────────────────────
    //  内部 — 反射初始化
    // ─────────────────────────────────────────────────────────────────

    private static void initIrisCoreReflection() throws Exception {
        if (iris_isPackInUseQuick != null && iris_getIrisConfig != null) return;

        Class<?> iris = Class.forName(IRIS_MAIN_CLASS);

        // Iris.isPackInUseQuick()
        try {
            iris_isPackInUseQuick = iris.getMethod("isPackInUseQuick");
        } catch (NoSuchMethodException ignored) {
            iris_isPackInUseQuick = null;
        }

        // Iris.getIrisConfig()
        try {
            iris_getIrisConfig = iris.getMethod("getIrisConfig");
            Class<?> irisConfigClass = Class.forName("net.irisshaders.iris.config.IrisConfig");

            irisConfig_shouldAllowUnknownShaders = irisConfigClass.getMethod("shouldAllowUnknownShaders");
            irisConfig_setUnknown = irisConfigClass.getMethod("setUnknown", boolean.class);
        } catch (Throwable t) {
            // Iris present but internals changed
            iris_getIrisConfig = null;
            irisConfig_shouldAllowUnknownShaders = null;
            irisConfig_setUnknown = null;
        }
    }

    private static void initIrisApiReflection() {
        if (irisApi_isShaderPackInUse != null) return;

        for (String clsName : IRIS_API_CLASSES) {
            try {
                Class<?> cls = Class.forName(clsName);
                irisApiInstance = cls.getMethod("getInstance").invoke(null);
                irisApi_isShaderPackInUse = cls.getMethod("isShaderPackInUse");
                return;
            } catch (Throwable ignored) {
            }
        }

        irisApiInstance = null;
        irisApi_isShaderPackInUse = null;
    }
}
