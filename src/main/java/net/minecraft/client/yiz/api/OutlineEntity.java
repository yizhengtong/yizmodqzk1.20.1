package net.minecraft.client.yiz.api;

/**
 * 通用实体描边接口：实现该接口的实体渲染时自动描边（无需注册）。
 * 返回描边色 {@code [r,g,b,a]}（0-1），{@code null} 表示不描边。
 * 动态描边（如锁定目标）请用 {@link OutlineRegistry#register} 注册 Provider。
 */
public interface OutlineEntity {

    /** 描边色 [r,g,b,a]；null = 不描边。 */
    float[] getOutlineColor();
}
