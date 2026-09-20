package net.minecraft.client.yiz.creature;

import net.minecraft.resources.ResourceLocation;

/**
 * 生物原型 —— 一份可复用的配置单元：id + 可选父原型 + 组件集合。
 *
 * <p>代码轨通过 {@link CreatureProfileRegistry#register} 登记，数据包轨解析 JSON 后走同一入口。
 * 父原型用于类级继承：子原型只声明差异，解析时沿父链合并（子覆盖父）。</p>
 */
public final class CreatureProfile {

    private final ResourceLocation id;
    private final ResourceLocation parent;
    private final ComponentMap components;

    private CreatureProfile(ResourceLocation id, ResourceLocation parent, ComponentMap components) {
        this.id = id;
        this.parent = parent;
        this.components = components;
    }

    public ResourceLocation id() {
        return id;
    }

    /** 父原型 id；无则 null。 */
    public ResourceLocation parent() {
        return parent;
    }

    public ComponentMap components() {
        return components;
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    public static Builder builder(String namespace, String path) {
        return new Builder(new ResourceLocation(namespace, path));
    }

    public static final class Builder {
        private final ResourceLocation id;
        private ResourceLocation parent;
        private ComponentMap components = ComponentMap.EMPTY;

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        public Builder parent(ResourceLocation parent) {
            this.parent = parent;
            return this;
        }

        public Builder parent(String namespace, String path) {
            this.parent = new ResourceLocation(namespace, path);
            return this;
        }

        public <T> Builder component(ComponentType<T> type, T value) {
            this.components = this.components.with(type, value);
            return this;
        }

        public Builder components(ComponentMap map) {
            if (map != null) this.components = map;
            return this;
        }

        public CreatureProfile build() {
            return new CreatureProfile(id, parent, components);
        }
    }

    @Override
    public String toString() {
        return "CreatureProfile[" + id + (parent != null ? " parent=" + parent : "") + "]";
    }
}
