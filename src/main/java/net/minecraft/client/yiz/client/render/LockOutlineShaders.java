package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * 锁定描边着色器注册：
 * - {@code rendertype_lock_fill} — 实体穿墙纯白填充（渲染到专用 FBO）
 * - {@code rendertype_lock_post} — 全屏后处理：采样 FBO 做边缘检测 + 发光
 * 由 {@code ShaderEventRegistrar.onRegisterShaders} 注册。
 */
public final class LockOutlineShaders {

    public static ShaderInstance lockFill;
    public static ShaderInstance lockPost;

    private LockOutlineShaders() {}

    public static ShaderInstance getLockFill() { return lockFill; }
    public static ShaderInstance getLockPost() { return lockPost; }

    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
            new ShaderInstance(event.getResourceProvider(),
                new ResourceLocation("yizmodqzk", "rendertype_lock_fill"),
                DefaultVertexFormat.NEW_ENTITY),
            s -> lockFill = s);
        event.registerShader(
            new ShaderInstance(event.getResourceProvider(),
                new ResourceLocation("yizmodqzk", "rendertype_lock_post"),
                DefaultVertexFormat.POSITION_TEX),
            s -> lockPost = s);
    }
}
