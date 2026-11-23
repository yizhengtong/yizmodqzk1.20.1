package net.minecraft.client.yiz.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * {@link ModelPart#cubes} 是 private，但编辑器的包围盒必须用模型自己的真实 cube 几何
 * （min/max 就是 1/16 格单位的盒子边界），所以用 accessor 暴露出来。
 */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {

    @Accessor("cubes")
    List<ModelPart.Cube> yizmodqzk$getCubes();
}
