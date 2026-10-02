#version 150

// 围绕实体的游离电弧（模型几何驱动）— 顶点着色器
// 与 rendertype_lightning_surface.vsh 同构：几何是实体模型自己的顶点（骨骼姿势变换已在 Position 里），
// 因此弧天然贴着四肢/头部/盔甲的轮廓，而不是碰撞箱。
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
out vec3 vLocalPos;    // 骨骼姿势变换后顶点（相对实体 origin）

void main() {
    vLocalPos = Position;
    vec4 mv = ModelViewMat * vec4(Position, 1.0);
    vViewPos = mv.xyz;
    vNormal = normalize(mat3(ModelViewMat) * Normal);
    vColor = Color;
    vUV = UV0;
    gl_Position = ProjMat * mv;
}
