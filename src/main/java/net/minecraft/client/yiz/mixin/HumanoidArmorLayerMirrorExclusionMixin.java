package net.minecraft.client.yiz.mixin;

import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.yiz.modelmirror.MirrorExclusion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让"人形盔甲层渲染期"对模型几何镜像可见，使盔甲（含盔甲纹饰 / 附魔光效）<b>不参与</b>贴模型特效。
 *
 * <p><b>为什么盔甲要排除</b>：盔甲与手持物同属"挂在身上的外挂网格"，不是实体体表 ——
 * 用户明确不想让装备本体被烧。而且盔甲层是独立贴图 / 独立 UV、独立 mesh
 * （{@code armor_cutout_no_cull} + {@code armor_cutout_no_cull_trim}），被烧出来是"盔甲形状的火舌"，
 * 与"烧模型下半身"的诉求无关；留着它只会让火在盔甲上再糊一遍。</p>
 *
 * <p>只注入人形盔甲层（{@code HumanoidArmorLayer}）：这是玩家/僵尸/骷髅等绝大多数盔甲的渲染入口，
 * 头盔/胸甲/护腿/靴子与纹饰全在它内部完成。其它装备层（马铠 {@code HorseArmorLayer}、
 * 鞘翅 {@code ElytraLayer}、羊驼装饰等）<b>刻意不动</b> —— 它们不是"盔甲"这个诉求所指，
 * 少改一个 vanilla 类就少一份与其它 MOD 冲突的风险；如果以后确实需要，按同一套标志加一处注入即可。</p>
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMirrorExclusionMixin {

    /** {@code HumanoidArmorLayer.render(PoseStack, MultiBufferSource, int, T extends LivingEntity, float×6)} */
    private static final String RENDER_DESC =
        "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I"
        + "Lnet/minecraft/world/entity/LivingEntity;FFFFFF)V";

    @Inject(method = RENDER_DESC, at = @At("HEAD"))
    private void yizmodqzk$mirrorExclusionArmorEnter(CallbackInfo ci) {
        MirrorExclusion.enterArmor();
    }

    @Inject(method = RENDER_DESC, at = @At("RETURN"))
    private void yizmodqzk$mirrorExclusionArmorExit(CallbackInfo ci) {
        MirrorExclusion.exitArmor();
    }
}
