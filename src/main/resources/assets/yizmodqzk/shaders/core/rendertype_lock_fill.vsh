#version 150
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec2 UV2;
in vec3 Normal;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec4 vColor;
out vec2 vUV0;
void main() {
    vColor = Color;      // 描边色（由 LockOutlineBufferSource 覆盖为实体描边色）
    vUV0 = UV0;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
