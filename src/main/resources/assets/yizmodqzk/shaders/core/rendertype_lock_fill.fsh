#version 150
// ═══════════════════════════════════════════════════════════════════════════════════════════
//  ⚠️⚠️ 这个 JSON/fsh 的"blend 节点"是本 pass 的**唯一安全绳，绝不能再删** ⚠️⚠️
//
//  2026-10-04 第三轮（黑屏回退）：第二轮曾把 `rendertype_lock_fill.json` 的 `"blend"` 节点
//  删掉（理由是"掩码不该混合，让 Java 侧用 disableBlend 强制不混合"）。那一步**主动踩了本工程
//  档案里已经钉死的黑屏坑**，与 2/3 号闪电层整屏发黑是同一个结构（档案原文见
//  `LightningRenderer` 的"⚑ 黑屏档案"）：
//
//    1.20.1 的 `ShaderInstance#apply()`（ShaderInstance.java:337）**每次 draw 都调**
//    `this.blend.apply()`；而 JSON 缺 `blend` 节点时
//    `parseBlendNode(null)`（ShaderInstance.java:250-252）返回 `new BlendMode()`
//    —— 那是 `opaque = true` 的默认值（BlendMode.java:31-33）。
//    `BlendMode#apply()` 对不透明模式做的是（BlendMode.java:43-53）：
//        if (lastApplied == null || this.opaque != lastApplied.isOpaque()) {
//            lastApplied = this;
//            if (this.opaque) { RenderSystem.disableBlend(); return; }   // ← 全局关混合，且不设 blendFunc
//        }
//    ⇒ **着色器程序自己把 RenderType 刚设好的混合状态关掉**，而且 `lastApplied` 这个静态缓存
//      只在"不透明标志变化"时才更新 ⇒ 它记的状态和 GL 真实状态可以长期不一致，之后
//      `equals(lastApplied)` 的短路会让某个 pass **跳过 enableBlend()**（永不自愈）。
//
//  本文件现在的写法（= 黑屏前那一版能跑的写法）：JSON 声明一条 **`opaque = false`** 的混合，
//  于是 `blend.apply()` 永远走不到那句 `disableBlend()`；"掩码不混合"这件事由 RenderType 的
//  `NO_TRANSPARENCY`（setup 时 disableBlend）负责，**不靠改 JSON、也不准再引入
//  `MASK_NO_BLEND` 那种在 clear 里也 disableBlend 的自定义 shard**。
//  要重做"按贴图 alpha 镂空"请看 `LockOutlineRenderType` 类注释里的安全路线。
// ═══════════════════════════════════════════════════════════════════════════════════════════
uniform sampler2D Sampler0;
in vec4 vColor;
in vec2 vUV0;
out vec4 fragColor;
void main() {
    // 纹理过滤：实体主纹理 alpha 透明（镂空）区不填充（不描边），
    // 只填充实际可见形状 → 后处理描边沿纹理可见区域边缘
    //
    // ⚠️ 现状（第三轮回退后）：这条 discard **在运行期基本是空操作** —— 见
    // `LockOutlineRenderType` / `LockOutlineBufferSource` 类注释里的根因：
    //   ① `Sampler0` 是原版的保留取样器名字空间，每次 draw 都被
    //      `VertexBuffer#_drawWithShader` 用全局槽位 `RenderSystem.getShaderTexture(0)` 重写；
    //   ② 所有实体层共用渲染器主贴图（`getTextureLocation`），而各层 UV 属于各自的贴图
    //      ⇒ 用主贴图采别人的 UV 会落在不透明区 ⇒ alpha≈1 ⇒ 整片被填。
    //  所以现在的观感 = **实心掩码**（"会描无纹理部位"那个已知观感问题）。
    //  这是"宁可牺牲描边形状，也不能黑屏"的取舍；重做镂空的路线写在 `LockOutlineRenderType`。
    vec4 tex = texture(Sampler0, vUV0);
    if (tex.a < 0.1) discard;
    // 输出描边色（含 alpha，供后处理采样颜色/透明度）
    fragColor = vColor;
}
