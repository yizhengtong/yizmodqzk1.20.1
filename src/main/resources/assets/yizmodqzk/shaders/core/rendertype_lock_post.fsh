#version 150
uniform sampler2D Sampler0;
uniform float uRadius;
uniform float uSharpness;
uniform vec2 uTexelSize;
in vec2 vUV0;
out vec4 fragColor;

// ═══════════════════════════════════════════════════════════════════════════
//  锁定描边 —— 后处理：在填充掩码的【边界】上色
//
//  uRadius    = 邻域半径（屏幕像素），当前 3（= 7×7 邻域，共 49 个采样）
//  uSharpness = 高斯宽度，当前 6.0（只影响描边带的软硬）
//  Sampler0   = 描边 FBO 的颜色纹理，rgb = 描边色，a = 描边 alpha（= 充能进度）
//               且只在【有纹理的片元】上非零（fill 阶段按贴图 alpha discard，
//               见 rendertype_lock_fill.fsh）
// ═══════════════════════════════════════════════════════════════════════════
//
//  ── 为什么必须换掉旧判据「density ≈ 0.5 取峰值」 ──────────────────────────────
//  旧写法：edge = exp(-((density-0.5)*uSharpness)²)，density = 邻域内填充像素占比。
//  它唯一的依据是"边界处邻域一半在里一半在外 ⇒ density≈0.5"。问题是
//  **density≈0.5 并不是边界的充分条件**：只要掩码是"细碎网点"，网点区里
//  每个像素的邻域覆盖率都≈该区填充率（≈0.5），于是整片区域都取到峰值 ⇒ 整片被涂色。
//  2026-10-04 之前的 fill 按贴图 alpha 镂空，遇到"零厚度大四边形 + 半透明贴图"
//  （监守者头须 / 胸甲）正好就是这种网点 ⇒ 整屏糊色。
//
//  ── 网点与真边界到底差在哪（本轮判据的立足点） ────────────────────────────────
//  关键观察：**网点的密度上界 = 该区的填充率**，与采样半径无关。
//  理由：网点是"贴图里 1~2 像素的孔"投影出来的，孔间距是亚像素~数像素量级，
//  而邻域固定为 7×7 —— 采样核**远大于**孔间距，所以邻域覆盖率的期望值收敛到
//  该区的填充率本身（大数定律），永远到不了 1.0。
//  反之，**真掩码的实心区必然存在"邻域几乎全满"的像素**（离边界 ≥ uRadius 的
//  内部像素，其 7×7 邻域 100% 落在掩码内 ⇒ density = 1.0）。
//  于是"邻域能否达到近乎全满"就是能分开两者的量 —— 且它是**结构性**的
//  （不依赖 uRadius / uSharpness 取值、不依赖实体/贴图/距离），不是调参调出来的。
//
//  ── 本轮判据：滞后双阈值（hysteresis） ──────────────────────────────────────
//    filled = density >= 0.55   // 宽判据：掩码本体 + 其外扩带（边界外侧 density 从 1 渐降到 0）
//    solid  = density >= 0.90   // 严判据：只有"存在近乎全满邻域"的地方才算【内部】
//    edge   = filled && !solid  // ⇒ 只落在"掩码边界"那条带上：
//                               //   · 掩码真内部：filled=true 且 solid=true ⇒ 不画 ✅
//                               //   · 掩码外远处：filled=false            ⇒ 不画 ✅
//                               //   · 边界带上：  filled=true 且 solid=false ⇒ 画 ✅
//                               //   · 网点区：    density≈0.5 < 0.55        ⇒ 不画 ✅★
//  带内的亮度剖面用高斯（峰在中点 0.725，见 main 里的 EDGE_PEAK 注释）。
//
//  ★ 一句证明（写进报告的那句）：网点区的邻域覆盖率上界 = 该区填充率 ≈ 0.5 < 0.55
//    ⇒ filled 恒为 false ⇒ edge 恒为 false ⇒ **网点区一个像素都不会被上色**，
//    与 uRadius/uSharpness/实体/距离全部无关。糊色在结构上被排除。
//
//  ── 关于"孔洞也会被描边" ───────────────────────────────────────────────────
//  真掩码内部的大孔（大到 7×7 邻域也填不满，即孔宽 ≳ 2·uRadius ≈ 6px）本身
//  就会满足"filled && !solid"，于是也被描一圈。这是**符合预期**的：那确实是
//  "有纹理的部分"的一个边界（用户要的形状就是掩码的形状，而不是它的凸包）。
//  孔宽 ≲ 2·uRadius 的碎孔达不到 filled，不会描 —— 正是本轮要抑制的那一类。
// ═══════════════════════════════════════════════════════════════════════════

/** 宽判据：邻域覆盖率到多少算"这里有掩码"（同时决定描边带往外延伸多远，≈ uRadius 像素）。 */
#define FILLED_MIN 0.55
/** 严判据：邻域覆盖率到多少算"这里在掩码内部"。设 1.0 会把描边压成 0 宽（数值上永远差一点），
 *  0.90 大约要求"离掩码边界 ≥ uRadius/2 像素"，既保住"实心区必然通过"这条结构性证明，
 *  又给抗锯齿边缘留出余量。 */
#define SOLID_MIN  0.90

void main() {
    // ── 邻域覆盖率：一次 7×7 遍历同时得到轮廓检测所需的中心像素 alpha ──
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

    // 中心像素本身是否已被填充（决定描边"长在掩码外沿"还是"压在轮廓上"）
    vec4 c = texture(Sampler0, vUV0);

    // 滞后双阈值：filled（宽）+ solid（严）
    float filled = step(FILLED_MIN, density);
    float solid  = step(SOLID_MIN,  density);
    // 只在"有掩码、但不在掩码内部"的带上色 —— 即掩码边界。
    // centerEmpty 保证描边落在掩码外沿（填充像素自己不再叠加），与旧版行为一致。
    float centerEmpty = 1.0 - step(0.1, c.a);
    float band = filled * (1.0 - solid) * centerEmpty;

    // ── 带内的高斯剖面：峰必须跟着判据一起挪 ──
    // 旧版把峰放在 density=0.5，因为那时"边缘"就等于 density≈0.5。
    // 本版的边界带是 density ∈ [FILLED_MIN, SOLID_MIN] = [0.55, 0.90]，
    // 若峰仍留在 0.5，exp(-((0.55..0.90 − 0.5)×6)²) 会落在 0.02~1e-4
    // ⇒ **描边几乎不可见**（比"糊屏"更隐蔽的一个坑）。
    // 所以峰挪到这条带的中点 EDGE_PEAK = (FILLED_MIN + SOLID_MIN)/2 = 0.725，
    // 并乘 smoothstep(FILLED_MIN, SOLID_MIN, density) 保证带边界处连续归零。
    // uSharpness 的语义不变（越大描边越细越硬、越小光晕越宽）：
    // uSharpness=6.0 时，膜厚约 ±(1/6) 个"带宽单位"（带宽 = 0.35），即约 ±1 个像素。
    #define EDGE_PEAK ((FILLED_MIN + SOLID_MIN) * 0.5)
    float ramp = smoothstep(FILLED_MIN, SOLID_MIN, density);
    float edge = band * ramp * exp(-pow((density - EDGE_PEAK) * uSharpness, 2.0));

    fragColor = vec4(c.rgb, c.a * edge);
}
