#version 150

// 闪电火花顶点着色器 — billboard quad（拖尾方向由 SparkGeometry 在顶点位置 + UV 编码）
in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 vUV;
out vec4 vColor;
out float vViewDist;   // 顶点距相机距离（近距淡出用）

void main() {
    vUV = UV0;
    vColor = Color;
    vViewDist = length(Position);
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
