#version 150

// ═══════════════════════════════════════════════════════════════════════════
//  贴模型火焰 —— 片元着色器（"着火"贴合实体实际模型）
//
//  与 rendertype_flame_volume.fsh 的关系：
//    ① 噪声/湍流/色带<b>完全复用</b>（Simplex3D + 5 层脊状湍流 + 三段火色），保证两种形态观感一致；
//    ② 积分方式不同：体积版对包围盒做 slab 求交、沿<b>视线</b>整段穿射；
//       本版起点是<b>模型表面片元本身</b>，沿"表面法线 × 视线"的混合方向<b>短程向外</b>步进
//       （10~16 步、约 0.4 格），于是火焰是从身体表面舔出来的，而不是罩着一个方盒；
//    ③ 坐标系不同：实体渲染期间 ModelViewMat 是单位阵、顶点已是视图空间（见 .vsh 注释），
//       所以步进在视图空间做（深度截断的 clip 坐标也随 t 线性，免去逐样本矩阵乘），
//       只有在采样噪声时才用 IViewRotMat 反旋转回世界/实体局部空间。
//
//  复用体积火焰的既有做法：预乘 alpha 输出、uSteps/uOctaves 按屏幕覆盖率 LOD、
//  uCenterCull 第一人称只画四周边缘、Sampler0（主目标深度纹理）+ ScreenSize 逐样本场景深度截断。
//
//  uniform 约定：
//    uParams  : x=噪声尺度 y=上升流速 z=密度/发光（<b>越小越透、越不糊</b>） w=湍流阈值（<b>越高越斑驳、空隙越大</b>）
//    uParams2 : x=步进长度(格) y=表面外偏移(格) z=法线/视线混合(0=纯法线) w=透明度上限
//    uParams3 : x=边缘衰减起点(|N·V|) y=边缘衰减终点(|N·V|) z=向外衰减指数（越大火舌越短越虚）
//               w=轮廓处最低强度（0 = 轮廓完全不烧，会把往外舔的火舌一起削掉，别设 0）
//    uSpread  : 蔓延进度 0→1（从下往上爬满模型），由 FlameEffects.SurfaceBurning 驱动
//    uSpreadHeight : 模型高度（格），把"模型局部高度"归一化到 0~1 再和 uSpread 比
//
//  "不要烧满"（用户反馈：下半身被均匀糊满、像套了层壳）的四个旋钮，调参看这里：
//    ① 斑驳程度 = uParams.w（阈值）：湍流超过它才算火舌；调高 → 火舌之间出现真正烧不到的空隙；
//    ② 整体浓度 = uParams.z（密度）：每步吸收率；本 pass 光程只有 0.42 格，它一高就整片饱和成壳。
//       配套 uParams2.w（透明度上限）：留出"能看见身体本身颜色/材质"的余量；
//    ③ 边缘柔和度 = uParams3.x/y（|N·V| 区间）+ uParams3.w（轮廓处最低强度）+ uParams3.z（向外衰减指数）：
//       轮廓处平滑压暗、外沿按指数收尾，于是外边界是渐隐的而不是硬切；
//    ④ 火线前沿柔和度 = SPREAD_SOFT（常量）：烧到哪的过渡带宽度。
// ═══════════════════════════════════════════════════════════════════════════

#define MAX_STEPS   16      // 步数上限（实际由 uSteps 决定）
#define MAX_OCTAVES 5       // 湍流倍频上限（实际由 uOctaves 决定）

// ── 场景深度截断的三个容差（见 main 里的说明；都是 NDC 深度 0~1 空间的量）──
#define DEPTH_BIAS       0.00015   // 逐样本截断的容差（原先 0.00003 ≈ 浮点噪声量级，掠射角下整段被切）
#define SELF_VISIBLE_EPS 0.00040   // fragDepth 与 sceneDepth 差多少以内算"本片元自己就是可见表面"
#define RELAX_STEPS      2         // 可见且面向相机的片元：起点附近无条件放行的步数

// ── 蔓延进度的可见起点 ──
// uSpread 由 Java 侧从 0 线性推到 1（0 = 刚点着）。若直接拿 0 当火线前沿，整具身体只有极少数
// 噪声列会亮 —— 观感就是"施法了但什么都没发生"。映射到 [0.15, 1]：施法瞬间火线 = 0.15*1.25-0.25
// ≈ -0.06（贴着模型最底部），脚边立刻有一圈明确的火，随后仍按原速率向上爬满（1 → 火线 1.0）。
#define SPREAD_FLOOR 0.15

// ── 火线前沿的柔和宽度（归一化高度）──
// 火线以下 = 已烧到。这个宽度是"从全透明到全强度"的过渡带：0.3 只有一条窄缝（看起来像水位线，
// 边缘偏硬），0.42 = 前后各留出约 1/5 身高的渐隐区，于是"烧到哪"是晕开的、不是一刀切。
// 想更"实"就调小，想更"烟熏渐隐"就调大。
#define SPREAD_SOFT 0.42

in vec3  vViewPos;
in vec3  vViewNormal;
in vec3  vWorldNormal;
in float vWorldHeight;
in float vFade;

// 矩阵必须在本 stage 也声明（GLSL 的 uniform 是逐 stage 的）
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;

uniform float iTime;
uniform int   uSteps;
uniform int   uOctaves;

// 第一人称"只画四周边缘"：屏幕中心半径小于该值的片元直接跳过步进（0 = 关闭）。
// 与体积火焰同一套约定（本 pass 在第一人称通常没有几何，留作 MOD 显示本地模型时的保护）
uniform float uCenterCull;

uniform vec3  uCamWorld;      // 相机世界坐标（视图空间 → 世界要加回它）
uniform vec3  uNoiseAnchor;   // 噪声锚点 = 实体脚部世界坐标 → 采样落在实体局部空间
uniform float uAnchorYaw;     // 实体渲染朝向：把采样点反向旋转，火焰图案钉在身体坐标系里

uniform float uSpread;        // 蔓延进度 0→1（0 = 还没烧起来，1 = 爬满）
uniform float uSpreadHeight;  // 模型高度（格）

uniform vec4  uParams;
uniform vec4  uParams2;
uniform vec4  uParams3;

uniform vec3  uColorCore;
uniform vec3  uColorMid;
uniform vec3  uColorEdge;

// 主渲染目标的深度纹理 + 屏幕尺寸（由绘制路径自动喂入，见 VertexBuffer#_drawWithShader）
uniform sampler2D Sampler0;
uniform vec2 ScreenSize;

out vec4 fragColor;

// ───────────────────────────── 噪声 ─────────────────────────────

vec3 mod289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 mod289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 permute(vec4 x) { return mod289(((x * 34.0) + 1.0) * x); }
vec4 taylorInvSqrt(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

// Simplex 3D（返回值约在 [-1,1]）—— 与体积火焰逐字一致
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

// 脊状湍流：多层 |noise| 反相叠加，归一化到 [0,1]（与体积火焰一致）
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

// ───────────────────────────── 蔓延 / 火色 ─────────────────────────────

// 蔓延进度（含可见起点，见 SPREAD_FLOOR）：火线位置与整体亮度都以它为基准。
float spreadAmount() {
    return mix(SPREAD_FLOOR, 1.0, clamp(uSpread, 0.0, 1.0));
}

// 蔓延遮罩：spreadAmount() 是"火线"在模型上的归一化高度前沿，火线以下 = 已烧到。
// 前沿不能是一条水平直线 —— 用"按身体局部列取样 + 随时间抖动"的低成本扰动把边缘打成不规则锯齿，
// 于是火是"一簇簇爬上去"的，而不是像水位线一样整齐上升；过渡带宽度见 SPREAD_SOFT。
//
// <b>蔓延上限（用户反馈：烧满全身很丑）</b>：火线在 uSpread=1 时停在身体<b>下半部分</b> ——
// line = -0.20 + spreadAmount*0.62 → uSpread=1 时 0.42（≈42% 身高），再叠加列噪声 ±0.15，
// 最高约 57% 身高；<b>上半身与头不烧</b>。起点（uSpread≈0）落在脚踝附近，刚点着就是"脚边一圈火"。
float spreadMask(vec3 lp) {
    float hNorm = clamp(lp.y / max(uSpreadHeight, 0.05), 0.0, 1.0);
    float colNoise = hash12(floor(vec2(lp.x, lp.z) * 3.0) + floor(iTime * 1.2) * 7.0);
    float front = hNorm + (colNoise - 0.5) * 0.30;      // 列噪声 ±0.15
    float line = -0.20 + spreadAmount() * 0.62;         // uSpread=1 → 0.42（只烧下半身）
    return 1.0 - smoothstep(line, line + SPREAD_SOFT, front);
}

// 温度色带：外焰(暗红) → 中层(橙黄) → 核心(白热)
vec3 fireRamp(float t) {
    vec3 c = mix(uColorEdge, uColorMid, smoothstep(0.0, 0.55, t));
    return mix(c, uColorCore, smoothstep(0.55, 1.0, t));
}

// ───────────────────────────── 主流程 ─────────────────────────────

void main() {
    // ── 第一人称"只画四周边缘"（与体积火焰同一实现；中心区域完全透明，绝不留雾状图案）──
    vec2  uv = gl_FragCoord.xy / ScreenSize;
    float centerMask = 1.0;
    if (uCenterCull > 0.001) {
        float centerR = length(uv - vec2(0.5)) * 2.0;
        centerMask = smoothstep(uCenterCull, uCenterCull * 2.0, centerR);
        if (centerMask <= 0.02) {
            fragColor = vec4(0.0);
            return;
        }
    }

    // ── 步进起点/方向（视图空间：相机在原点，Position 已经是"视图旋转后"的坐标）──
    vec3 nView = normalize(vViewNormal);
    vec3 ro = vViewPos + nView * uParams2.y;      // 抬离表面一点：避免和本体深度/精度打架
    vec3 viewRay = normalize(-vViewPos);          // 相机 → 片元
    // 纯法线 = 火焰完全垂直表面往外舔；混入视线 = 火焰顺着视线铺开（掠射角下更饱满）
    vec3 rd = normalize(mix(nView, viewRay, clamp(uParams2.z, 0.0, 1.0)));

    // ── 轮廓/边缘的透明度衰减：让火的<b>外边界渐隐</b>，而不是硬切一刀 ──
    // facing = 本片元表面"朝向相机的程度"：正对相机 ≈ 1，处在部件轮廓（掠射）≈ 0。
    // 轮廓处的片元正是往外舔得最远的那批（它们的步进方向几乎与视线垂直 → 投影到屏幕上就是
    // 超出身体轮廓的那一圈火）；以前它们满强度一直烧到几何尽头，看起来就是一层带硬边的火壳。
    // 这里按 facing 平滑压暗到 uParams3.w（<b>不压到 0</b>：压到 0 会把往外舔的火舌一起削掉，
    // 只剩贴着表面的一层发光），配合指数 falloff 一起把边界做成渐隐。
    float facing = clamp(dot(nView, viewRay), 0.0, 1.0);
    float edgeFade = mix(uParams3.w, 1.0,
                         smoothstep(uParams3.x, max(uParams3.y, uParams3.x + 1.0e-3), facing));

    int   steps = clamp(uSteps, 4, MAX_STEPS);
    float dt    = max(uParams2.x, 0.02) / float(steps);

    // ── 场景深度截断（本通道不写也不测硬件深度，遮挡全靠这里）──
    // 采样在视图空间等距 → clip 坐标也随 t 线性，端点算一次即可。
    // 副作用（正是我们要的）：身体自己已经写进深度缓冲，于是"背面的火"会被身体挡住。
    float sceneDepth = texture(Sampler0, uv).r;
    float fragDepth  = gl_FragCoord.z;    // 本片元自己的深度（几何与本体逐字节相同 → 也就是本体表面深度）
    vec4  clipRo = ProjMat * ModelViewMat * vec4(ro, 1.0);
    vec4  clipRd = ProjMat * ModelViewMat * vec4(rd, 0.0);

    // "本片元自己是不是一块可见、且面向相机的表面"：满足它的片元，沿法线外移的采样点一定落在
    // 相机这一侧的本体表面之外，放宽起点截断不会看到任何"本该被挡住的东西"；
    // 背面 / 被其它部位或墙挡住的片元两条都不满足（fragDepth 明显大于 sceneDepth，或法线背离相机）
    // → 一步都不放宽，穿身而过的 X 光火依旧不存在。
    bool selfVisible = (fragDepth - sceneDepth) <= SELF_VISIBLE_EPS && dot(nView, vViewPos) < 0.0;

    // 抖动起点：把固定步长的层状条纹打散成噪点
    float jitter = hash12(gl_FragCoord.xy + fract(iTime) * 137.0);

    // 片元在模型上的归一化高度：越靠下越白热，越往上越橙红（火往上爬的观感）
    float fragH = clamp((vWorldHeight - uNoiseAnchor.y) / max(uSpreadHeight, 0.05), 0.0, 1.0);

    vec3  acc   = vec3(0.0);
    float trans = 1.0;

    for (int i = 0; i < steps; i++) {
        float t = (float(i) + 0.35 + jitter * 0.6) * dt;

        vec4  clip = clipRo + clipRd * t;
        float sampleDepth = (clip.z / clip.w) * 0.5 + 0.5;
        // 起点附近的截断要放宽（否则整片火可能一个采样都过不去 → 零贡献）：
        // 本 pass 的几何与本体逐字节相同，起点又只沿法线外移 uParams2.y（0.045 格 ≈ 1~2 步），
        // 所以最前面几步的采样深度和本体深度几乎相等；掠射角、法线背离视线、深度纹理精度、
        // MSAA 解析误差，随便哪一个都能让第一次比较就 sampleDepth > sceneDepth → break。
        // 两条放行规则：
        //   ① 容差用 DEPTH_BIAS（比原先 3e-5 大一个量级，仍远小于"身体厚度"的深度跨度）；
        //   ② selfVisible 的片元前 RELAX_STEPS 步无条件放行 —— 那几步只落在本体表面往外 ~0.1 格内。
        // 被挡住 / 背面的片元 selfVisible = false：一步都不放行，截断行为与以前完全一致。
        if (sampleDepth > sceneDepth + DEPTH_BIAS && !(selfVisible && i < RELAX_STEPS)) break;

        vec3 pView = ro + rd * t;
        vec3 world = uCamWorld + IViewRotMat * pView;

        // 实体局部坐标（噪声锚定 + 反向旋转到身体坐标系 → 实体转身/走动时火焰图案都钉在身体上）
        vec3 lp = world - uNoiseAnchor;
        if (uAnchorYaw != 0.0) {
            float c = cos(-uAnchorYaw);
            float s = sin(-uAnchorYaw);
            lp.xz = mat2(c, s, -s, c) * lp.xz;
        }

        float spread = spreadMask(lp);
        if (spread <= 0.01) continue;         // 火还没爬到这里

        vec3 q = lp * uParams.x;
        q.y -= iTime * uParams.y;             // 向上流动（噪声坐标下移 → 视觉上火焰上升）

        float turb   = turbulence(q);
        float tongue = smoothstep(uParams.w, 1.0, turb);   // 阈值化 → 火舌而不是均匀雾
        if (tongue <= 0.002) continue;

        // 沿步进方向衰减：越往外越淡（火焰向外散开、自动收尾，不会有生硬的截断面）。
        // 用指数曲线而不是原来的直线：末段拖尾更长、前段掉得更快，于是火舌是"从体表挑起再散开"，
        // 而不是"均匀一层铺到固定长度后齐刷刷断掉"。uParams3.z 越大越短越虚。
        float falloff = exp(-uParams3.z * (float(i) / float(steps)));
        // 朝下的面少烧一点：火是往上走的（世界法线的 Y 分量；纯几何修正，几乎不花算力）
        float upBias = mix(0.7, 1.0, clamp(vWorldNormal.y * 0.5 + 0.5, 0.0, 1.0));
        // 蔓延越充分整体略亮（只保留一点点动态，否则"烧得越久越白"会把整个火焰冲成白热）
        float d = spread * tongue * falloff * upBias * edgeFade * (0.85 + 0.15 * spreadAmount());
        if (d <= 0.002) continue;

        // 前向合成：本段吸收率 → 贡献 → 衰减透过率（与体积火焰同一套公式）
        float a = 1.0 - exp(-d * uParams.z * dt);
        // 温度由"舌心程度 + 高度"决定，<b>不能</b>拿当地密度当温度：火体内部密度普遍偏大，
        // 那样会把整团火都判成白热，观感上"明亮部分逐渐占据全部位置"（不符合火焰）。
        // 现在只有舌心（tongue→1）且靠近身体下部的地方才是白热，外围与上部保持暗红—橙。
        float temp = clamp(tongue * (0.40 + 0.60 * (1.0 - fragH)), 0.0, 1.0);
        acc   += trans * fireRamp(temp) * a;
        trans *= (1.0 - a * 0.9);
        if (trans < 0.03) break;              // 已经接近不透明，后面的采样看不见了
    }

    float alpha = clamp((1.0 - trans) * vFade * uParams2.w * centerMask, 0.0, 1.0);
    fragColor = vec4(acc * centerMask, alpha);
}
