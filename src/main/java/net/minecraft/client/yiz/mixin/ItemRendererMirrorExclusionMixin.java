package net.minecraft.client.yiz.mixin;

import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.yiz.modelmirror.MirrorExclusion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让"物品渲染期"对模型几何镜像可见，使手持物 / 掉落物 / 展示框物品<b>不参与</b>贴模型特效
 * （用户要求：实体身上的物品不要有灼烧特效）。
 *
 * <p><b>为什么注入 {@code ItemRenderer.render} 而不是各个 {@code renderStatic} 重载</b>：
 * 1.20.1 里所有物品模型渲染都汇聚到这一个方法 ——
 * {@code renderStatic(ItemStack×8参)} → {@code renderStatic(LivingEntity×10参)} → {@code render(...)}；
 * 三人称手持物（{@code ItemInHandLayer}）、第一人称手（{@code ItemInHandRenderer.renderItem}）、
 * 掉落物（{@code ItemEntityRenderer}）、展示框（{@code ItemFrameRenderer}）、GUI 图标全部经由它，
 * 一处注入即可全覆盖，不用逐个重载写（少一处漏一处）。</p>
 *
 * <p><b>为什么退出用 {@code @At("RETURN")}</b>：覆盖方法内所有 return（物品为空时会提前返回），
 * 保证"进入/退出"成对，标志不会残留。真的残留了也只是"物品不再被抄"（本就是期望行为），不会画错几何。</p>
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMirrorExclusionMixin {

    /** {@code ItemRenderer.render(ItemStack, ItemDisplayContext, boolean, PoseStack, MultiBufferSource, int, int, BakedModel)} */
    private static final String RENDER_DESC =
        "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Z"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II"
        + "Lnet/minecraft/client/resources/model/BakedModel;)V";

    @Inject(method = RENDER_DESC, at = @At("HEAD"))
    private void yizmodqzk$mirrorExclusionEnter(CallbackInfo ci) {
        MirrorExclusion.enterItem();
    }

    @Inject(method = RENDER_DESC, at = @At("RETURN"))
    private void yizmodqzk$mirrorExclusionExit(CallbackInfo ci) {
        MirrorExclusion.exitItem();
    }
}
