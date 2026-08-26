#version 150

// 体表闪电顶点着色器 — 输出视图空间法线 + 相机空间位置（供 fsh 边缘检测与闪电路径）
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV1;
in vec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vColor;
out vec2 vUV;
out vec3 vNormal;      // 视图空间法线
out vec3 vViewPos;     // 相机空间位置
out vec3 vLocalPos;    // 骨骼姿势变换后顶点（相对实体 origin，跟随骨骼动画 + 稳定不随实体移动）

void main() {
    vLocalPos = Position;   // ModelPart 提交时已含骨骼 PoseStack 变换（动画姿势），相对实体
    vec4 mv = ModelViewMat * vec4(Position, 1.0);
    vViewPos = mv.xyz;
    vNormal = normalize(mat3(ModelViewMat) * Normal);
    vColor = Color;
    vUV = UV0;
    gl_Position = ProjMat * mv;
}
