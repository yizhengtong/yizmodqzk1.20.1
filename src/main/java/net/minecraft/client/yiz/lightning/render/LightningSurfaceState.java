package net.minecraft.client.yiz.lightning.render;

import net.minecraft.client.yiz.api.ShockedEntityAPI;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 体表闪电的"这个实体现在带不带电、多亮"查询 —— 从旧的
 * {@code LivingEntityRendererSurfaceLightningMixin} 原样搬出来的判定逻辑（行为不变），
 * 只是从"渲染时内联"改成"消费者查询"。
 *
 * <p>两个来源取较大值：</p>
 * <ol>
 *   <li><b>技能触发式感电</b>：服务端 {@code S2CShockFxPayload} 注册的实体，由 {@link ShockedEntityAPI}
 *       给出渐显渐隐系数（前 6 tick 渐显、后 6 tick 渐隐、中间满）；</li>
 *   <li><b>被动带电指示</b>：玩家身上 {@code SHOCK_ATTACK} / {@code SHOCK_DEFENSE} 属性大于 0 时，
 *       给一个固定低亮度（{@link #CHARGER_FADE}），表示"这个人带电"。</li>
 * </ol>
 *
 * <p>不可见实体一律返回 0：原版对 invisible 实体走的是 {@code itemEntityTranslucentCull} + alpha 0.15
 * 的"隐约可见"通道，在它身上叠闪电会暴露隐形单位的位置（旧实现同样显式跳过隐身）。</p>
 */
public final class LightningSurfaceState {

    /**
     * 被动带电的固定强度（旧实现里的 {@code charger ? 0.3f : 0f}）：只是"低亮度指示"，
     * 技能触发式感电才会满亮度。
     */
    private static final float CHARGER_FADE = 0.3f;

    private LightningSurfaceState() {}

    /**
     * 本帧的体表闪电强度。
     *
     * @return 0 = 完全不画；其余为 0~1 的强度（顶点透明度 + ColorModulator 都用它）
     */
    public static float fadeOf(Entity entity) {
        if (entity == null || entity.isInvisible()) return 0.0f;

        float fade = 0.0f;
        // 感电状态（技能触发）：isShocked 判存在、getFade 取渐显渐隐系数
        if (ShockedEntityAPI.isShocked(entity.getId())) {
            fade = Math.max(fade, ShockedEntityAPI.getFade(entity.getId()));
        }
        if (entity instanceof Player player && hasShockCharge(player)) {
            fade = Math.max(fade, CHARGER_FADE);
        }
        return fade;
    }

    /** 玩家是否被动带电（两个感电属性任一 > 0）。1.20.1：RegistryObject → .get()。 */
    private static boolean hasShockCharge(Player player) {
        var attack = player.getAttribute(YizAttributes.SHOCK_ATTACK.get());
        var defense = player.getAttribute(YizAttributes.SHOCK_DEFENSE.get());
        return (attack != null && attack.getValue() > 0.0)
                || (defense != null && defense.getValue() > 0.0);
    }
}
