#version 150

// 球状闪电顶点着色器 — billboard quad
in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 vUV;
out vec4 vColor;
out float vViewDist;

void main() {
    vUV = UV0;
    vColor = Color;
    vViewDist = length(Position);
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
