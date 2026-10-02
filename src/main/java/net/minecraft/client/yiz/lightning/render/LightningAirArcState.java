package net.minecraft.client.yiz.lightning.render;

import net.minecraft.client.yiz.lightning.LightningFX;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 「围绕实体的空气电弧」的<b>状态表</b> —— 与 {@code ShockedEntityAPI}（技能感电的体表电流）同构，
 * 但用途不同：前者是"持续带电"（渐显渐隐、几何走 {@code LightningSurfaceLayer}），
 * 本类是"命中瞬间/技能指定在目标身上炸开的短寿命游离弧"。
 *
 * <p><b>为什么需要它（用户要求"空气电弧也要跟着模型轮廓"）</b>：旧实现把这类弧做成
 * {@code SurfaceArcEffect} —— 端点全部落在实体<b>包围盒六个面</b>上（还会生成跨面宽 40~80% 的长弧），
 * 于是会飘在模型之外（头顶上方、脚下方那些盒面上），看上去就是"围着一个方盒子跳的电"。
 * 现在改为"注册状态 → 由模型几何镜像的 {@code LightningAirArcLayer} 用<b>实体模型自己的顶点</b>
 * 画出来"，弧贴在四肢/头部/盔甲的轮廓上，与 {@code LightningSurfaceLayer} 用的是同一份几何
 * （差别只在着色器与寿命/参数，见 {@code LightningAirArcLayer}）。</p>
 *
 * <p><b>仍然留在空气中的那类</b>：两个实体之间的链式电弧（{@code LightningFX.spawnArc}）与
 * 球状闪电表面的弧（{@code spawnSurfaceArcOnBall}）—— 它们本来就不属于"某个实体的体表"，
 * 前者是两点之间的折线、后者连实体都不是（{@code BallEffect}），镜像层无法表达（见
 * {@link net.minecraft.client.yiz.lightning.fx.SurfaceArcEffect} 的类注释）。</p>
 */
public final class LightningAirArcState {

    /** 单个实体的游离弧参数（remaining 递减到 0 即失效）。 */
    private record Entry(float remaining, float maxLife, float halfWidth, float r, float g, float b) {}

    private static final ConcurrentHashMap<Integer, Entry> STATES = new ConcurrentHashMap<>();
    /** 表上限，防御性清理。 */
    private static final int MAX_ENTRIES = 256;

    private LightningAirArcState() {}

    /**
     * 给实体挂一段"围绕身体的空气电弧"（本帧起生效，几何 = 该实体的模型）。
     *
     * @param life      寿命（秒；渲染端按 tick 递减，与其它特效一致 1 tick = 0.05s）
     * @param halfWidth 弧半宽（世界单位，仅作参数留档；模型弧的线宽由着色器按 UV 决定）
     */
    public static void put(Entity entity, float life, float halfWidth, float r, float g, float b) {
        if (entity == null || life <= 0f) return;
        if (STATES.size() > MAX_ENTRIES) STATES.clear();
        // 同一实体重复施加：取较长寿命 + 后者参数（连击刷新时颜色换成最新那次）
        STATES.merge(entity.getId(),
                new Entry(life, life, halfWidth, r, g, b),
                (old, now) -> new Entry(Math.max(old.remaining(), now.remaining()),
                        Math.max(old.maxLife(), now.maxLife()), now.halfWidth(), now.r(), now.g(), now.b()));
    }

    /** 便捷入口：默认蓝白。 */
    public static void put(Entity entity, float life) {
        put(entity, life, 0.028f, LightningFX.DEFAULT_R, LightningFX.DEFAULT_G, LightningFX.DEFAULT_B);
    }

    /** 本帧强度系数 [0,1]（两端淡入淡出：前 20% 渐显、后 30% 渐隐）。0 = 不画。 */
    public static float fadeOf(int entityId) {
        Entry e = STATES.get(entityId);
        if (e == null || e.remaining() <= 0f) return 0f;
        float elapsed = e.maxLife() - e.remaining();
        float fadeIn = clamp01(elapsed / Math.max(0.05f, e.maxLife() * 0.2f));
        float fadeOut = clamp01(e.remaining() / Math.max(0.05f, e.maxLife() * 0.3f));
        return Math.min(fadeIn, fadeOut);
    }

    public static boolean isActive(int entityId) {
        Entry e = STATES.get(entityId);
        return e != null && e.remaining() > 0f;
    }

    public static float halfWidthOf(int entityId) {
        Entry e = STATES.get(entityId);
        return e == null ? 0.028f : e.halfWidth();
    }

    /** 弧颜色（线性 0..1）：layer 把它喂给 {@code uArcColor}。 */
    public static float[] colorOf(int entityId) {
        Entry e = STATES.get(entityId);
        if (e == null) return new float[]{LightningFX.DEFAULT_R, LightningFX.DEFAULT_G, LightningFX.DEFAULT_B};
        return new float[]{e.r(), e.g(), e.b()};
    }

    /** 客户端每 tick：全体剩余 -1（0.05s），到期移除。 */
    public static void tick() {
        STATES.replaceAll((id, e) -> new Entry(e.remaining() - 0.05f, e.maxLife(), e.halfWidth(), e.r(), e.g(), e.b()));
        STATES.values().removeIf(e -> e.remaining() <= 0f);
    }

    public static void clear() { STATES.clear(); }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
