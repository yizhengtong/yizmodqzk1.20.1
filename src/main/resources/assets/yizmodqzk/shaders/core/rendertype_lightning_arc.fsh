#version 150

// 闪电电弧片段着色器 — 程序化电流 + 近距淡出（防挡玩家视线）
in vec2 vUV;
in vec4 vColor;
in float vViewDist;

uniform float iTime;
uniform vec4 ColorModulator;

out vec4 fragColor;

float hash(float n) { return fract(sin(n) * 43758.5453123); }

float vnoise(float x) {
    float i = floor(x), f = fract(x);
    float u = f * f * (3.0 - 2.0 * f);
    return mix(hash(i), hash(i + 1.0), u);
}

void main() {
float ay = abs(vUV.y);
    float core = pow(1.0 - ay, 1.6);
    float white = smoothstep(0.35, 0.0, ay);

    // 端点辉光：u 接近 0(from)/1(to) 时 core 变宽变亮，消除裸线头突兀感
    float endGlow = smoothstep(0.05, 0.0, vUV.x) + smoothstep(0.95, 1.0, vUV.x);
    core += endGlow * pow(1.0 - ay, 0.5) * 0.6;
    white += endGlow * smoothstep(0.5, 0.0, ay) * 0.3;

    float flow = vUV.x * 10.0 - iTime * 8.0;
    float n = vnoise(floor(flow));
    float flick = 0.55 + 0.45 * n;
    float jag = vnoise(flow * 3.0 + iTime * 20.0);
    flick *= 0.6 + 0.4 * jag;

    // 方向脉冲：沿 from→to 缓慢移动的尖锐亮带，强化电流方向流动感
    float pulseBright = pow(0.5 + 0.5 * sin((vUV.x * 4.0 - iTime * 5.0) * 6.28318), 4.0);
    flick = clamp(flick + pulseBright * 0.4, 0.0, 1.5);

    vec3 col = mix(vColor.rgb, vec3(1.0), white * 0.9);
    float intensity = core * flick;
    // 近距淡出：距相机 <0.5 全透、>1.5 正常，避免眼前电弧挡视线
    float alpha = intensity * smoothstep(0.5, 1.5, vViewDist);
    fragColor = vec4(col * intensity, alpha) * ColorModulator;
}
