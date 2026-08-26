#version 150

// 闪电火花片段着色器 — 各向异性亮点(cosmic2 Star 风格) + 沿 v 轴拖尾
// v 轴对齐运动方向：vUV.y=0 头端(亮)、vUV.y=1 尾端(暗)
in vec2 vUV;
in vec4 vColor;
in float vViewDist;

uniform float iTime;
uniform vec4 ColorModulator;

out vec4 fragColor;

void main() {
    vec2 p = vUV * 2.0 - 1.0;

    // 亮点核心：cosmic2 Star() 风格 — 中心爆亮(0.01/(r+0.001)) + 边缘 smoothstep 平滑归零
    float len = length(p);
    float core = max(0.0, 0.01 / (len + 0.001));
    core *= smoothstep(1.0, 0.1, len);

    // 各向异性拖尾：v 轴尾端渐暗、垂直方向急速收尖
    float tailFalloff = smoothstep(1.0, -1.0, vUV.y * 2.0 - 1.0);
    float perpPinch = pow(1.0 - abs(p.x), 2.0);

    // 高频闪烁（火花寿命短，需快闪）
    float flick = 0.55 + 0.45 * fract(sin(vUV.x * 50.0 + iTime * 15.0) * 43758.5453);

    // 核心向白色收，继承电弧颜色
    vec3 col = mix(vColor.rgb, vec3(1.0), smoothstep(0.5, 1.0, core) * 0.9);

    float intensity = (core * 0.6 + tailFalloff * perpPinch * 0.8) * flick;
    float alpha = intensity * smoothstep(0.5, 1.5, vViewDist);   // 近距淡出（同 arc/ball）
    fragColor = vec4(col * intensity, alpha) * ColorModulator;
}
