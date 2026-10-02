#version 150

// ═══════════════════════════════════════════════════════════════════════════
//  3D 透明火焰 —— 体积渲染片元着色器
//
//  技术栈：
//    ① Simplex 3D 噪声（Ashima/Gustavson 标准实现）—— 无网格方向性伪影，自然随机形状；
//    ② 脊状湍流 turbulence：5 层 |noise| 反相叠加（freq×2.03 / amp×0.5）—— 出"火焰舌"细节；
//    ③ 射线穿射采样 ray marching：片元侧对包围盒做 slab 求交，沿视线定步长积分，
//       前向 alpha 合成（emission/absorption）→ 真正的体积感（不是贴图 billboard）；
//    ④ 形状遮罩 shapeMask：同一套噪声/积分，四种形态只换遮罩（火墙/火环/火绳/着火）；
//    ⑤ 透明混合：输出<b>预乘 alpha</b>，json 里 blend = (one, 1-srcalpha)。
//
//  为什么几何按世界坐标生成：Position 即世界坐标 → 射线起点直接用 uCameraWorld，
//  免去"相机在局部空间"的矩阵求逆；噪声落在世界空间，火焰会穿过世界流动。
//
//  uniform 约定：
//    uParams  : x=噪声尺度  y=流动速度(格/tick 归一)  z=密度/发光强度  w=湍流阈值
//    uParams2 : x=半径或壳厚  y=高度(垂直尺度)  z=跨度(长度/范围)  w=透明度上限
//    uShape   : 0=火墙 1=火环 2=火绳 3=着火
//    uPoints  : 火绳折线控制点（世界坐标，最多 8 点）；其余形态忽略
// ═══════════════════════════════════════════════════════════════════════════

#define MAX_STEPS   28      // 步数上限（实际步数由 uSteps 决定，见下）
#define MAX_OCTAVES 5       // 湍流倍频上限（实际层数由 uOctaves 决定）

in vec3 vWorldPos;
in vec4 vColor;

// 矩阵必须在本 stage 也声明（GLSL 的 uniform 是逐 stage 的，json 里声明不等于着色器里可见）。
// 这里用它们把"相机相对世界坐标"投影到 clip → 与深度纹理比较（见 main 里的场景深度截断）。
uniform mat4  ModelViewMat;
uniform mat4  ProjMat;

uniform float iTime;
// 步数与湍流层数（CPU 按屏幕覆盖率自适应降质：火焰铺满屏幕时 28×5 次 3D simplex 是纯粹的浪费）。
// 动态循环在 GLSL 1.50 合法；这里再夹一次上下限，防外部传入离谱值。
uniform int   uSteps;
uniform int   uOctaves;

// 第一人称"只画四周边缘"：屏幕中心半径小于该值的片元直接跳过射线步进（0 = 关闭）。
// 相机陷在火里时，中心那一大块既没有信息量又最贵（整屏像素 × 全步数积分）；
// 只保留四周一圈火舌，观感反而更像"身上着火"，帧率也从 12 恢复到可用水平。
uniform float uCenterCull;

uniform vec3  uCameraWorld;
// 相机世界坐标：几何是"相机相对坐标"，噪声必须加回它才锚定在世界空间，
// 否则相机一动火焰纹理就跟着游动（火看起来不贴在世界里）。
uniform vec3  uCamWorld;
// 噪声锚点（世界坐标）与锚定偏航，支持两种连接方式：
//   a) 世界固定：uNoiseAnchor = (0,0,0)、uAnchorYaw = 0
//      → 噪声采样于绝对世界坐标，火焰图案钉死在世界里（同一处火墙永远同一张花纹）。
//   b) 追踪实体：uNoiseAnchor = 实体当前世界位置、uAnchorYaw = 实体偏航变化
//      → 噪声采样于实体局部坐标，火焰图案随实体平移/转身一起走
//      （否则实体会"穿过"一张静止的噪声场，看起来火和实体是两回事）。
uniform vec3  uNoiseAnchor;
uniform float uAnchorYaw;

uniform vec3  uBoxMin;
uniform vec3  uBoxMax;

uniform vec3  uOrigin;
uniform vec3  uAxis;

uniform vec3  uP0;
uniform vec3  uP1;
uniform vec3  uP2;
uniform vec3  uP3;
uniform vec3  uP4;
uniform vec3  uP5;
uniform vec3  uP6;
uniform vec3  uP7;
uniform float uPointCount;

uniform float uShape;
uniform vec4  uParams;
uniform vec4  uParams2;

uniform vec3  uColorCore;
uniform vec3  uColorMid;
uniform vec3  uColorEdge;

uniform vec4  ColorModulator;

// 主渲染目标的深度纹理 + 屏幕尺寸（都由绘制路径自动喂入）。
// 用它在射线步进里<b>逐样本</b>与场景深度比较：本通道关掉了硬件深度测试，
// 因为包围盒面只是代理几何，其深度没有物理意义 —— 拿它做深度测试会导致
// "火与方块相交时只剩边界、盒子内部直接透空"。
uniform sampler2D Sampler0;
uniform vec2 ScreenSize;

out vec4 fragColor;

// ───────────────────────────── 噪声 ─────────────────────────────

vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

// Simplex 3D（返回值约在 [-1,1]）
float snoise(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);

    vec3 i  = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);

    vec3 g  = step(x0.yzx, x0.xyz);
    vec3 l  = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);

    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;

    i = mod289(i);
    vec4 p = permute(permute(permute(
                 i.z + vec4(0.0, i1.z, i2.z, 1.0))
               + i.y + vec4(0.0, i1.y, i2.y, 1.0))
               + i.x + vec4(0.0, i1.x, i2.x, 1.0));

    float n_ = 0.142857142857;
    vec3 ns = n_ * D.wyz - D.xzx;

    vec4 j  = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);

    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);

    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);

    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));

    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;

    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);

    vec4 norm = taylorInvSqrt(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;

    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

// 脊状湍流：多层 |noise| 反相叠加，归一化到 [0,1]。
// 用 |noise| 反相（1-|n|）而不是原始 n：得到"脊线"——火焰舌/卷须的典型形态。
// 层数由 uOctaves 控制（近距离大面积时降到 3 层，肉眼看不出差别但省一半以上算力）。
float turbulence(vec3 p) {
    int octaves = clamp(uOctaves, 2, MAX_OCTAVES);
    float sum = 0.0, amp = 0.55, f = 1.0, norm = 0.0;
    for (int i = 0; i < octaves; i++) {
        sum  += amp * (1.0 - abs(snoise(p * f)));
        norm += amp;
        f    *= 2.03;          // 非整数倍频，避免各层峰谷对齐产生格状纹理
        amp  *= 0.5;
    }
    return sum / norm;
}

float hash12(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// ───────────────────────────── 形状 ─────────────────────────────

// 折线距离（火绳用）：逐段点到线段距离取最小。
// 控制点用 8 个独立 vec3 uniform 而不是数组：1.20.1 的 Uniform 只保证 set(x,y,z) 这类标量接口，
// 数组统一走 set(float[]) 不保险；8 个点对火绳（鞭/绳索）已经足够表达弯曲。
vec3 ropePoint(int i) {
    if (i == 0) return uP0;
    if (i == 1) return uP1;
    if (i == 2) return uP2;
    if (i == 3) return uP3;
    if (i == 4) return uP4;
    if (i == 5) return uP5;
    if (i == 6) return uP6;
    return uP7;
}

float ropeDistance(vec3 p, int count) {
    float best = 1e9;
    for (int i = 0; i < 7; i++) {
        if (i >= count - 1) break;
        vec3 a = ropePoint(i);
        vec3 b = ropePoint(i + 1);
        vec3 ab = b - a;
        float t = clamp(dot(p - a, ab) / max(dot(ab, ab), 1e-4), 0.0, 1.0);
        best = min(best, length(p - (a + ab * t)));
    }
    return best;
}

// 形状遮罩：返回 0..1 的"可燃区域"权重（0 = 体积之外，直接跳过采样）
float shapeMask(vec3 p) {
    int   s     = int(uShape + 0.5);
    vec3  d     = p - uOrigin;
    float thick = max(uParams2.x, 0.01);
    float hgt   = max(uParams2.y, 0.01);
    float span  = max(uParams2.z, 0.01);

    if (s == 0) {
        // ── 火墙：沿 uAxis 的竖直长条；法向厚度 thick，长度 span，高度 hgt
        vec3 ax = normalize(vec3(uAxis.x, 0.0, uAxis.z) + vec3(1e-4, 0.0, 1e-4));
        vec3 n  = normalize(cross(vec3(0.0, 1.0, 0.0), ax));
        float across = 1.0 - smoothstep(thick * 0.45, thick, abs(dot(d, n)));
        float along  = 1.0 - smoothstep(span * 0.62, span, abs(dot(d, ax)));
        float up     = clamp(1.0 - d.y / hgt, 0.0, 1.0);
        float down   = clamp(1.0 + d.y / (thick * 1.5), 0.0, 1.0);
        return across * along * up * down;
    }
    if (s == 1) {
        // ── 火环：水平环（XZ 平面上的圆壳），半径 thick 的"圆柱面"，高度 hgt
        float radial = length(d.xz);
        float shell  = 1.0 - smoothstep(thick * 0.35, thick, abs(radial - span));
        float up     = clamp(1.0 - d.y / hgt, 0.0, 1.0);
        float down   = clamp(1.0 + d.y / (thick * 1.5), 0.0, 1.0);
        return shell * up * down;
    }
    if (s == 2) {
        // ── 火绳：沿 uPoints 折线的管状体积，半径 thick
        int count = int(uPointCount + 0.5);
        if (count < 2) return 0.0;
        float dist = ropeDistance(p, count);
        return 1.0 - smoothstep(thick * 0.4, thick, dist);
    }
    // ── 着火：绕实体竖轴的<b>圆</b>包络（半径 thick），从下往上舔舐。
    //    必须用 length（欧氏距离）而不是 max(|x|,|z|)（切比雪夫）—— 后者是"方形壳"，
    //    会让每一团火看起来是个小方块（"着火就是个方正方块"的根因就在这里）。
    float radial = length(d.xz);
    float shell  = 1.0 - smoothstep(thick * 0.55, thick, radial);
    float up     = clamp(1.0 - d.y / hgt, 0.0, 1.0);
    float down   = clamp(1.0 + d.y / max(thick, 0.3), 0.0, 1.0);
    return shell * up * down;
}

// 体积密度场：形状遮罩 × 湍流火焰舌。
// heatOut 输出"舌心程度"（0 = 外围、1 = 舌心），供温度映射使用 —— 温度必须由舌心与高度决定，
// <b>不能</b>拿当地密度当温度：火体内部密度普遍偏大，那样每个采样点都会被判成白热，
// 表现就是"火焰当中明亮的部分逐渐占据全部位置"（越烧越白、不再像火）。
float densityAt(vec3 p, out float heatOut) {
    heatOut = 0.0;
    float mask = shapeMask(p);
    if (mask <= 0.002) return 0.0;

    // 两种锚定方式的统一写法：
    //   世界固定 → uNoiseAnchor=(0,0,0)、yaw=0 → 采样点 = 绝对世界坐标；
    //   追踪实体 → uNoiseAnchor=实体位置、yaw=实体转角 → 采样点 = 实体局部坐标（随实体平移/转身）。
    vec3 wp = p + uCamWorld - uNoiseAnchor;
    if (uAnchorYaw != 0.0) {
        float c = cos(-uAnchorYaw);
        float s = sin(-uAnchorYaw);
        wp.xz = mat2(c, s, -s, c) * wp.xz;   // 噪声坐标反向旋转 → 花纹随实体转向
    }
    vec3 q = wp * uParams.x;
    q.y -= iTime * uParams.y;   // 向上流动（噪声坐标下移 → 视觉上火焰上升）

    float turb   = turbulence(q);
    // 阈值化：只有湍流高位区才有密度 → 不透明的"舌"而不是均匀雾
    float tongue = smoothstep(uParams.w, 1.0, turb);
    if (tongue <= 0.002) return 0.0;

    // 竖直冷却：越靠顶部越稀（火焰上端散开），并叠一层低频扰动打破轮廓
    float cooling = 1.0 - 0.45 * clamp((p.y - uOrigin.y) / max(uParams2.y, 0.01), 0.0, 1.0);
    heatOut = tongue;                      // 舌心程度 → 温度映射用（见 main 里的 temp）
    return mask * tongue * cooling;
}

// 温度色带：外焰(暗红) → 中层(橙黄) → 核心(白热)
vec3 fireRamp(float t) {
    vec3 c = mix(uColorEdge, uColorMid, smoothstep(0.0, 0.55, t));
    return mix(c, uColorCore, smoothstep(0.55, 1.0, t));
}

// 射线-包围盒求交（slab 法；rd 分量为 0 时产生 inf，仍然正确）
bool rayBox(vec3 ro, vec3 rd, vec3 bmin, vec3 bmax, out float t0, out float t1) {
    vec3 inv = 1.0 / rd;
    vec3 a = (bmin - ro) * inv;
    vec3 b = (bmax - ro) * inv;
    vec3 lo = min(a, b);
    vec3 hi = max(a, b);
    t0 = max(max(lo.x, lo.y), lo.z);
    t1 = min(min(hi.x, hi.y), hi.z);
    return t1 > max(t0, 0.0);
}

// ───────────────────────────── 主流程 ─────────────────────────────

void main() {
    vec3 ro = uCameraWorld;
    vec3 rd = normalize(vWorldPos - ro);

    // 第一人称"自己着火"：只渲染摄像机的四周边缘。
    // 中心区域必须是<b>完全透明、没有任何图案</b>——曾经在这里加过一层"淡雾"以避免硬边，
    // 结果就是视野正中糊着一个橙色椭圆图案直接挡视线（那恰恰是要避免的东西）。
    // 形状为屏幕上的椭圆是符合预期的（用 UV 距离，不做宽高比校正）。
    vec2  uv = gl_FragCoord.xy / ScreenSize;
    float centerR = length(uv - vec2(0.5)) * 2.0;      // 0 = 正中；1 = 上下边缘中点
    float centerMask = 1.0;
    if (uCenterCull > 0.001) {
        centerMask = smoothstep(uCenterCull, uCenterCull * 2.0, centerR);
        if (centerMask <= 0.02) {
            fragColor = vec4(0.0);   // 中心：什么都不画（也省掉全部射线步进）
            return;
        }
    }

    float t0, t1;
    if (!rayBox(ro, rd, uBoxMin, uBoxMax, t0, t1)) {
        fragColor = vec4(0.0);
        return;
    }
    t0 = max(t0, 0.0);
    float span = t1 - t0;
    if (span <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }

    int steps = clamp(uSteps, 4, MAX_STEPS);
    float dt = span / float(steps);
    // 抖动起点：把固定步长的层状条纹（banding）打散成噪点，28 步也看不出分层
    float jitter = hash12(gl_FragCoord.xy + fract(iTime) * 137.0) * dt;

    // ── 场景深度截断（本通道不写、也不测硬件深度，遮挡全靠这里）──
    // 逐样本把自己的 NDC 深度与场景深度比较：采样点跑到地形/方块后面就停止积分。
    // 于是"火与方块相交"表现为被方块正确截断（而不是只剩边界或透空），
    // "火在墙后"则整段被截掉（自然不可见），也不需要任何硬件深度测试。
    float sceneDepth = texture(Sampler0, gl_FragCoord.xy / ScreenSize).r;
    // 采样的 clip 坐标随 t 线性变化 → 预先算好端点，循环里只做一次乘加
    vec4 clipRo = ProjMat * ModelViewMat * vec4(ro, 1.0);
    vec4 clipRd = ProjMat * ModelViewMat * vec4(rd, 0.0);

    vec3  acc   = vec3(0.0);   // 预乘后的累积辐射
    float trans = 1.0;         // 透过率

    for (int i = 0; i < steps; i++) {
        float t = t0 + jitter + float(i) * dt;

        vec4  clip = clipRo + clipRd * t;
        float sampleDepth = (clip.z / clip.w) * 0.5 + 0.5;
        if (sampleDepth > sceneDepth + 0.001) break;   // 已在场景几何之后，后续采样都看不见

        vec3  p = ro + rd * t;
        float heat;
        float d = densityAt(p, heat);
        if (d > 0.003) {
            // 前向合成：本段吸收率 → 贡献 → 衰减透过率
            float a = 1.0 - exp(-d * uParams.z * dt);
            // 温度：舌心越足 + 越靠火焰底部越热（白热只出现在舌心底部），外围与顶部保持暗红—橙。
            // 不要用 d 当温度（见 densityAt 的注释：那会让白热逐渐铺满整个火焰）。
            float base = 1.0 - clamp((p.y - uOrigin.y) / max(uParams2.y, 0.01), 0.0, 1.0);
            float temp = clamp(heat * (0.40 + 0.60 * base), 0.0, 1.0);
            acc   += trans * fireRamp(temp) * a;
            trans *= (1.0 - a * 0.9);
            if (trans < 0.02) break;      // 已经接近不透明，后面的采样看不见了
        }
    }

    // 自己着火（uCenterCull>0）时整体再压一档透明度：少挡视野，看得见路
    float viewFade = uCenterCull > 0.001 ? 0.75 : 1.0;
    float alpha = clamp((1.0 - trans) * vColor.a * uParams2.w * centerMask * viewFade, 0.0, 1.0);
    fragColor = vec4(acc * vColor.rgb * ColorModulator.rgb * centerMask * viewFade, alpha);
}
