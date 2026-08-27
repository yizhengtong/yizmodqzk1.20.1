#version 150
uniform vec4 uLockColor;
uniform sampler2D Sampler0;
in vec2 vUV0;
in vec3 vNormal;
in vec3 vViewPos;
in float vDown;
out vec4 fragColor;

const float RIM_EDGE = 0.4;    // 内部朝向面淡出起点（rim 越小越淡）
const float RIM_SOLID = 0.8;   // 外轮廓侧面不透明起点
const float DOWN_FADE = 0.9;   // 朝下面削弱强度（平视时腹部/胸底"向下膨胀"轮廓）
const float MIN_ALPHA = 0.08;

void main() {
    // 纹理过滤：实体主纹理 alpha 透明（镂空）区跳过描边，只描实际可见形状的边缘
    vec4 tex = texture(Sampler0, vUV0);
    if (tex.a < 0.1) discard;

    // 内/外侧判定：法线⊥视线（外轮廓侧面，膨胀投影窄）描边浓；
    // 法线∥视线（内部朝向面，如仰视时的胸/腰底面，投影宽）淡出甚至消失。
    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vViewPos);
    float rim = 1.0 - abs(dot(N, V));
    float mask = smoothstep(RIM_EDGE, RIM_SOLID, rim);
    // 朝下面（法线朝下，模型空间）也淡出：平视时腹部/胸底底面向下膨胀的轮廓
    mask *= 1.0 - vDown * DOWN_FADE;
    if (mask < MIN_ALPHA) discard;
    fragColor = vec4(uLockColor.rgb, uLockColor.a * mask);
}
