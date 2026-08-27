#version 150
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV2;
in vec3 Normal;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 uModelCenter;
uniform float uOutlineWidth;
out vec2 vUV0;
out vec3 vNormal;
out vec3 vViewPos;
out float vDown;
void main() {
    // 径向膨胀（模型空间）：沿"模型中心→顶点"方向，保持相邻面在棱角处共点不断裂。
    // uOutlineWidth 由渲染器按"相机距离"动态传入（∝ 距离），实现屏幕恒定描边宽度。
    vec3 dir = Position - uModelCenter;
    float len = length(dir);
    vec3 n = len < 1e-5 ? vec3(0.0, 1.0, 0.0) : dir / len;
    vec3 expanded = Position + n * uOutlineWidth;
    vUV0 = UV0;
    vNormal = mat3(ModelViewMat) * Normal;      // 视图空间面法线（用于内外侧判定）
    vDown = clamp(-Normal.y, 0.0, 1.0);          // 模型空间朝下程度：1=正朝下(底面)
    vec4 viewPos = ModelViewMat * vec4(expanded, 1.0);
    vViewPos = viewPos.xyz;                      // 视图空间膨胀位置
    gl_Position = ProjMat * viewPos;
}
