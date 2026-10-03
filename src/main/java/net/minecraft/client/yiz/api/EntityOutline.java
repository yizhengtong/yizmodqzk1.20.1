package net.minecraft.client.yiz.api;

import net.minecraft.client.yiz.client.render.LockOutlineRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * 实体描边公开 API（穿墙发光描边，FBO + 后处理）：
 * 任意实体可描边，锁定系统等效果通过本门面接入。
 *
 * 用法：
 * <ul>
 *   <li>固定描边：实体类实现 {@link OutlineEntity}，{@code getOutlineColor()} 返回 [r,g,b,a]（null=不描边）。</li>
 *   <li>按实体类型：{@link #registerDefault(EntityType, float[])}。</li>
 *   <li>动态判定：{@link #register(OutlineRegistry.Provider)}（如锁定目标随玩家视角变化）。</li>
 *   <li>全局调参：{@link #setWidth(int)}（描边屏幕像素半径）/ {@link #setSharpness(float)}（发光锐度）。</li>
 * </ul>
 * 描边效果：穿墙可见、屏幕固定宽度（远近一致）、发光晕。
 *
 * <p><b>描边的形状来自"模型几何轮廓"</b>（填充 FBO 里是实心的几何掩码，2026-10-04 起不再按实体贴图的
 * alpha 做镂空判定）：掩码必须是实心的，否则"半透明大四边形"（守界者头须/胸甲那类零厚度片）会在填充里
 * 变成细碎网点，后处理的邻域覆盖率整片落在峰值上 → 整片被涂成描边色。理由见
 * {@code rendertype_lock_fill.fsh}。</p>
 */
public final class EntityOutline {

    private EntityOutline() {}

    /** 设置全局描边宽度（屏幕像素半径，远近一致）。 */
    public static void setWidth(int px) {
        LockOutlineRenderer.setPostRadius(px);
    }

    /** 设置全局发光锐度（越大描边越细越硬，越小发光晕越宽）。 */
    public static void setSharpness(float sharpness) {
        LockOutlineRenderer.setEdgeSharpness(sharpness);
    }

    /** 注册动态描边提供者（按实体返回描边色 [r,g,b,a]，null=不描边）。 */
    public static void register(OutlineRegistry.Provider provider) {
        OutlineRegistry.register(provider);
    }

    /** 按实体类型注册默认描边色 [r,g,b,a]（该类型所有实例生效）。 */
    public static void registerDefault(EntityType<?> type, float[] color) {
        OutlineRegistry.registerDefault(type, color);
    }

    /** 查询实体的描边色；不描边返回 null。 */
    public static float[] getColor(Entity entity) {
        return OutlineRegistry.getOutlineColor(entity);
    }

    /** 实体是否需要描边。 */
    public static boolean isOutlined(Entity entity) {
        return getColor(entity) != null;
    }
}
