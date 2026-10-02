#version 150

// ═══════════════════════════════════════════════════════════════════════════
//  贴模型火焰 —— 顶点着色器（"着火"贴合实体实际模型的实现）
//
//  几何从哪来：不是包围盒，而是实体<b>自己的模型几何</b> —— 实体渲染时
//  （EntityRenderDispatcher.render → EntityRenderer.render）每个顶点被
//  ModelMirrorBufferSource 再抄一份到 rendertype_flame_surface 缓冲里，
//  所以 Position/Normal/Color 就是 ModelPart.renderToBuffer 原样写出的那一套。
//
//  坐标系（与体积火焰相反，务必看仔细）：
//    ① 实体的 PoseStack 已经把"视图旋转 + 相机相对平移 + 实体朝向 + 骨骼动画"全部烤进顶点，
//       所以 Position 是<b>视图空间</b>坐标（相机在原点），而不是"相机相对世界坐标"；
//    ② 实体渲染期间 ModelViewMat 是<b>单位阵</b>：关卡姿态只在渲染云/世界边界时才被
//       apply 到 RenderSystem 的 modelViewStack（见 LevelRenderer#renderLevel 尾部），
//       实体阶段栈顶仍是单位阵 —— 所以 gl_Position = ProjMat * ModelViewMat * Position
//       和实体本体完全同路，火焰与模型严丝合缝（这也是本 pass 不需要自己 push 矩阵的原因）；
//    ③ 世界坐标 / 世界法线必须用 IViewRotMat（逆视图旋转）反旋转：
//         world = uCamWorld + IViewRotMat * viewPos
//       噪声在实体局部空间采样、蔓延遮罩按模型局部高度推进，都依赖这一步。
// ═══════════════════════════════════════════════════════════════════════════

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform vec3 uCamWorld;

out vec3  vViewPos;      // 视图空间位置（相机在原点）——片元侧步进的起点/视线方向都在这儿算
out vec3  vViewNormal;   // 视图空间法线（归一化）——火焰沿它向外舔
out vec3  vWorldNormal;  // 世界空间法线（片元侧用来"朝下的面少烧一点"：火是往上走的）
out float vWorldHeight;  // 世界 Y（蔓延前沿按世界高度算，实体站在斜坡上也对）
out float vFade;         // 顶点色 alpha = 逐实体淡入淡出（由 Java 侧按寿命写入）

void main() {
    vViewPos = Position;

    // 关掉光照/受击红闪等顶点色影响，只取 alpha 做整体淡入淡出（RGB 由片元着色器的火色决定）
    vFade = Color.a;

    // 法线：NEW_ENTITY 的 Normal 是 BYTE 归一化值，ModelPart 施加的是 PoseStack 的法线矩阵，
    // 所以它同样处于视图空间；长度可能受缩放影响 → 自己再归一化一次，退化时兜一个 +Z。
    vec3 n = Normal;
    float len = length(n);
    vViewNormal  = len > 1.0e-4 ? n / len : vec3(0.0, 0.0, 1.0);
    vWorldNormal = IViewRotMat * vViewNormal;

    vec3 world = uCamWorld + IViewRotMat * Position;
    vWorldHeight = world.y;

    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
