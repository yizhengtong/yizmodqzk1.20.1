#version 150

// 围绕实体的游离电弧（模型几何驱动）—— 片元着色器。
//
// 几何 = 实体模型顶点（模型几何镜像双写），所以这里的"弧"只能沿模型表面走：
//   · vUV 给出该面在贴图上的路径（同一部件的 UV 是连续的）→ 弧沿它游走 = 贴在部件表面；
//   · vLocalPos 给一个"钉在身体上"的锚（骨骼动画后、相对实体），用来错开每条弧的相位；
//   · vNormal 让弧在掠射（侧面）处更亮，正对相机处收敛 —— 弧看起来是"绕着身体跳"而不是"糊在正面"。
//
// 与 rendertype_lightning_surface 的观感差别（这就是"两类弧"的区分）：
//   · 生死门控更密（更多、更碎的短弧），游走更快；
//   · 弧更细（0.004 而不是 0.008）、更亮、颜色由 uArcColor 指定（命中瞬间的弧可以按技能染色）。
uniform float iTime;
uniform vec4 ColorModulator;
uniform vec3 uArcColor;
uniform float uArcWidth;
uniform float uArcSeed;

in vec4 vColor;
in vec2 vUV;
in vec3 vNormal;
in vec3 vViewPos;
in vec3 vLocalPos;

out vec4 fragColor;

// ─── Simplex 2D 噪声 ───
vec3 mod289v3(vec3 x){return x-floor(x*(1.0/289.0))*289.0;}
vec2 mod289v2(vec2 x){return x-floor(x*(1.0/289.0))*289.0;}
vec3 permute(vec3 x){return mod289v3(((x*34.0)+1.0)*x);}
float snoise(vec2 v){
    const vec4 C=vec4(0.211324865405187,0.366025403784439,-0.577350269189626,0.024390243902439);
    vec2 i=floor(v+dot(v,C.yy));
    vec2 x0=v-i+dot(i,C.xx);
    vec2 i1=(x0.x>x0.y)?vec2(1.0,0.0):vec2(0.0,1.0);
    vec4 x12=x0.xyxy+C.xxzz; x12.xy-=i1;
    i=mod289v2(i);
    vec3 p=permute(permute(i.y+vec3(0.0,i1.y,1.0))+i.x+vec3(0.0,i1.x,1.0));
    vec3 m=max(0.5-vec3(dot(x0,x0),dot(x12.xy,x12.xy),dot(x12.zw,x12.zw)),0.0);
    m=m*m; m=m*m;
    vec3 x=2.0*fract(p*C.www)-1.0;
    vec3 h=abs(x)-0.5;
    vec3 ox=floor(x+0.5);
    vec3 a0=x-ox;
    m*=1.79284291400159-0.85373472095314*(a0*a0+h*h);
    vec3 g;
    g.x=a0.x*x0.x+h.x*x0.y;
    g.yz=a0.yz*x12.xz+h.yz*x12.yw;
    return 130.0*dot(m,g);
}

void main(){
    float t = iTime + uArcSeed;

    // 两端淡出（vUV.y∈[0,1] 是模型自身 UV，端点即部件边界）
    float edgeFade = smoothstep(0.0, 0.10, vUV.y) * smoothstep(1.0, 0.90, vUV.y);

    // 稀疏生死门控（比体表层密：命中瞬间要"炸开一串短弧"而不是一条长蛇）
    float gate = step(0.45, snoise(vec2(vUV.x * 0.9 + uArcSeed, t * 3.5)));

    // 沿 vUV.y 的连贯曲折路径（更快、更碎）
    float wave1 = sin(vUV.y * 11.0 + t * 3.0 + uArcSeed) * 0.07;
    float wave2 = snoise(vec2(vUV.y * 5.0 + uArcSeed, t * 1.8)) * 0.06;
    float path = 0.5 + wave1 + wave2;
    float dist = abs(vUV.x - path);

    // 线宽随 uArcWidth 缩放（0.028 默认 → 0.004 屏占比，细而亮）
    float w = clamp(uArcWidth * 0.14, 0.0015, 0.01);
    float intensity = smoothstep(w, 0.0, dist) * edgeFade * gate;

    // 掠射处更亮：|N·V| 小的部位是部件侧面，弧绕过去时最显眼
    float grazing = 1.0 - abs(dot(normalize(vNormal), normalize(-vViewPos)));
    intensity *= mix(0.55, 1.0, clamp(grazing, 0.0, 1.0));

    if (intensity < 0.01) discard;

    float core = smoothstep(0.65, 1.0, intensity);
    float halo = smoothstep(0.01, 0.65, intensity);
    // 电弧配色：技能色（uArcColor）为底 + 白热核心
    vec3 col = mix(uArcColor * 0.75, uArcColor, halo);
    col = mix(col, vec3(1.0), core);
    col *= 2.2;

    // alpha 乘上顶点色 alpha（= 本帧强度系数，来自 MirrorChannel 的顶点副本）
    fragColor = vec4(col, intensity * vColor.a) * ColorModulator;
}
