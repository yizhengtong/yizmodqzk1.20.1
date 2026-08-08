package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.tizMod;
import net.minecraft.client.yiz.tool.attribute.EntityAttributeGate;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AttributeInstance Mixin — 受保护属性防移除（1.20.1 移植版）。
 *
 * <p>拦截 {@link AttributeInstance#removeModifier(AttributeModifier)} —— 1.20.1 所有移除路径的唯一汇聚点
 * （removeModifier(UUID)/removeModifiers() 都委派到它，字节码验证）。对 modifier 的 UUID
 * （{@link AttributeModifier#getId()}）判定是否受保护（EntityAttributeGate 分配过的 yizmodqzk:prot_），
 * 做「调用栈 + 包名」鉴权：受信任放行，其他模组拒绝 → 实体挂载的属性不会被外部清掉。</p>
 *
 * <p>⚠️ 1.20.1 差异：removeModifier(AttributeModifier) 返回 void（1.21.1 的 ResourceLocation 版返回 boolean），
 * 用 {@link CallbackInfo} 而非 CallbackInfoReturnable，取消即跳过移除。</p>
 */
@Mixin(AttributeInstance.class)
public abstract class AttributeInstanceMixin {

    @Inject(method = "removeModifier(Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;)V",
            at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$guardProtectedRemove(AttributeModifier modifier, CallbackInfo ci) {
        if (modifier == null) return;
        if (!EntityAttributeGate.isProtectedUuid(modifier.getId())) return;  // 非受保护 UUID 零开销放行
        if (EntityAttributeGate.isCallerTrusted()) return;                   // 本家/引擎/白名单放行
        tizMod.LOGGER.warn("[AttributeGate] 拒绝外部移除受保护属性: {}", modifier.getId());
        ci.cancel();                                                          // 阻止移除
    }
}
