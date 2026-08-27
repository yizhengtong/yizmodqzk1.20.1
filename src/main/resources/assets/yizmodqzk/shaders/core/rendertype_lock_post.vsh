#version 150
in vec3 Position;
in vec2 UV0;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec2 vUV0;
void main() {
    vUV0 = UV0;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
