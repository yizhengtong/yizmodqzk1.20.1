#version 150
uniform sampler2D Sampler0;
in vec4 vColor;
in vec2 vUV0;
out vec4 fragColor;
void main() {
    // 纹理过滤：实体主纹理 alpha 透明（镂空）区不填充（不描边），
    // 只填充实际可见形状 → 后处理描边沿纹理可见区域边缘
    vec4 tex = texture(Sampler0, vUV0);
    if (tex.a < 0.1) discard;
    // 输出描边色（含 alpha，供后处理采样颜色/透明度）
    fragColor = vColor;
}
