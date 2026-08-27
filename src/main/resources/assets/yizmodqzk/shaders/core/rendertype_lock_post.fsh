#version 150
uniform sampler2D Sampler0;
uniform vec4 uLockColor;
uniform float uRadius;
uniform float uSharpness;
uniform vec2 uTexelSize;
in vec2 vUV0;
out vec4 fragColor;
void main() {
    // 邻域填充密度：像素在填充边缘带时 density≈0.5 → 高斯峰值 → 描边；
    // 填充内部(≈1) / 外部远处(≈0) → 透明。边缘带宽 = uRadius 屏幕像素（远近一致），
    // 高斯渐变形成原版光灵箭那种发光晕。穿墙由填充 pass 的 NO_DEPTH_TEST 保证。
    float sum = 0.0;
    float count = 0.0;
    for (float y = -uRadius; y <= uRadius; y += 1.0) {
        for (float x = -uRadius; x <= uRadius; x += 1.0) {
            float a = texture(Sampler0, vUV0 + vec2(x, y) * uTexelSize).a;
            sum += step(0.1, a);
            count += 1.0;
        }
    }
    float density = sum / count;
    float edge = exp(-pow((density - 0.5) * uSharpness, 2.0));
    fragColor = vec4(uLockColor.rgb, uLockColor.a * edge);
}
