package net.minecraft.client.yiz.creature;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;

/**
 * 组件类型 —— 生物配置的最小单位标识，绑定一个序列化 Codec 与可选缺省值。
 *
 * <p>对标 1.21 的 {@code DataComponentType<T>}：1.20.1 没有原版数据组件，
 * 这里自建等价物，用于把生物的属性、效果、战斗参数定义为可组合、可覆盖、可序列化的组件。</p>
 *
 * <p>相等性只按 {@link #id()} 判定：同一 id 重复注册会得到同一个逻辑类型，
 * Codec 与缺省值以首次注册为准。</p>
 *
 * @param <T> 组件值类型
 */
public final class ComponentType<T> {

    private final ResourceLocation id;
    private final Codec<T> codec;
    private final T defaultValue;

    ComponentType(ResourceLocation id, Codec<T> codec, T defaultValue) {
        this.id = id;
        this.codec = codec;
        this.defaultValue = defaultValue;
    }

    public ResourceLocation id() {
        return id;
    }

    public Codec<T> codec() {
        return codec;
    }

    /** 缺省值；未定义时返回 null（调用方自行决定回退语义）。 */
    public T defaultValue() {
        return defaultValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ComponentType<?> other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ComponentType[" + id + "]";
    }
}
