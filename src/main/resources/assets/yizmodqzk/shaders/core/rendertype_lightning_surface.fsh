#version 150

// 体表闪电 — vUV 路径连贯版（vUV∈[0,1] 确定范围；vLocalPos 是像素单位范围不可控，弃用）
uniform float iTime;
uniform vec4 ColorModulator;

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
    float t = iTime;

    // 两端淡出（vUV.y∈[0,1] 确定范围）
    float edgeFade = smoothstep(0.0, 0.15, vUV.y) * smoothstep(1.0, 0.85, vUV.y);

    // 稀疏生死门控（慢游离）
    float globalGate = step(0.62, snoise(vec2(vUV.x * 0.3, t * 2.5)));

    // 沿 vUV.y 纵向的连贯曲折路径
    float wave1 = sin(vUV.y * 6.0 + t * 1.5) * 0.08;
    float wave2 = snoise(vec2(vUV.y * 3.0, t * 0.8)) * 0.05;
    float lightningPath = 0.5 + wave1 + wave2;
    float dist = abs(vUV.x - lightningPath);

    float intensity = smoothstep(0.008, 0.0, dist) * edgeFade * globalGate;

    if (intensity < 0.01) discard;

    float core = smoothstep(0.7, 1.0, intensity);
    float halo = smoothstep(0.01, 0.7, intensity);
    vec3 white = vec3(1.0, 1.0, 1.0);
    vec3 neonBlue = vec3(0.25, 0.5, 1.0);
    vec3 electricPur = vec3(0.12, 0.04, 0.5);
    vec3 col = mix(electricPur, neonBlue, halo);
    col = mix(col, white, core);
    col *= 2.5;

    fragColor = vec4(col, intensity) * ColorModulator;
}
