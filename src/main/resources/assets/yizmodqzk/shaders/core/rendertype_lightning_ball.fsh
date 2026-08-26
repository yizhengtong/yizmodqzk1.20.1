#version 150

// 球状闪电 — 蓝白等离子球（billboard 伪 3D）
// 解决"像正方体框住一张图"：discard 圆外消除正方形轮廓 + 伪球面法线做菲涅尔边缘光，
// 把平面贴图压成有体积的球；fbm 沿球面流动 + 蓝白配色出等离子质感。
in vec2 vUV;
in vec4 vColor;
in float vViewDist;

uniform float iTime;
uniform vec4 ColorModulator;

out vec4 fragColor;

float hash21(vec2 s) {
    s = sin(s * vec2(123.45, 546.23)) * 345.21 + 12.57;
    return fract(s.x * s.y);
}

// 分形值噪声（fbm）
float fbm(vec2 uv, float octaves) {
    float sn = 0.0, amp = 1.0, den = 0.0;
    octaves = clamp(octaves, 1.0, 6.0);
    for (float i = 1.0; i <= octaves; i++) {
        vec2 g = smoothstep(0.0, 1.0, fract(uv));
        vec2 id = floor(uv);
        vec2 o = vec2(0.0, 1.0);
        float bl = hash21(id);
        float br = hash21(id + o.yx);
        float tl = hash21(id + o);
        float tr = hash21(id + o.yy);
        sn += mix(mix(bl, br, g.x), mix(tl, tr, g.x), g.y) * amp;
        den += amp;
        uv *= 2.0 + 1.5;
        amp *= 0.5;
    }
    return sn / den;
}

void main() {
    vec2 p = vUV * 2.0 - 1.0;        // [-1,1]，billboard 中心为原点
    float r = length(p);
    if (r > 1.0) discard;             // 圆形收敛 —— 消除"正方形框"轮廓

    // 伪 3D 球面法线：把平面 p 当单位球的屏幕投影，重建 z = sqrt(1-r²)
    float z = sqrt(max(0.0, 1.0 - r * r));
    vec3 n = normalize(vec3(p, z));

    // 球面流动等离子（fbm 沿球面坐标 + 时间流动）
    float plasma = fbm(n.xy * 4.0 + vec2(iTime * 0.8, -iTime * 0.6), 3.0);
    plasma += 0.5 * fbm(n.xy * 9.0 - vec2(iTime * 1.3, iTime * 0.9), 2.0);
    plasma = clamp(plasma / 1.5, 0.0, 1.0);

    // 菲涅尔边缘光（z 小=球边缘 → 亮），球体积感的关键
    float fresnel = pow(1.0 - z, 2.5);

    // 蓝白等离子配色：深处深蓝 → plasma 高处偏白热
    vec3 deepBlue = vec3(0.05, 0.15, 0.55);
    vec3 paleBlue = vec3(0.50, 0.70, 1.00);
    vec3 white    = vec3(1.00, 1.00, 1.00);
    vec3 col = mix(deepBlue, paleBlue, plasma);
    float core = smoothstep(0.6, 0.15, r);                          // 中心强
    col = mix(col, white, smoothstep(0.65, 1.0, plasma) * core);    // 等离子白热斑

    // 边缘菲涅尔蓝白光晕（球的轮廓发光）
    col += paleBlue * fresnel * 0.9;

    // 整体明暗：中心实、边缘渐淡（体积感）+ 等离子流动闪烁
    float intensity = mix(0.30, 1.0, core) + fresnel * 0.6;
    intensity *= 0.65 + 0.35 * plasma;

    float alpha = clamp(intensity, 0.0, 1.0) * (1.0 - smoothstep(0.85, 1.0, r)) * vColor.a;
    alpha *= smoothstep(0.5, 1.5, vViewDist);   // 近距淡出（同 arc/spark）
    fragColor = vec4(col * intensity, alpha) * ColorModulator;
}
