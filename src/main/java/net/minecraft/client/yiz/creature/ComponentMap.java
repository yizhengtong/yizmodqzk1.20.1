package net.minecraft.client.yiz.creature;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 组件集合（不可变）—— 一组「组件类型 → 值」的映射，用作生物配置的**原型**。
 *
 * <p>对标 1.21 的 {@code DataComponentMap}：来源可以是代码注册的默认值，
 * 也可以是数据包 JSON 解析后的结果，两者用同一容器表达。</p>
 *
 * <p>实例化后不可变；所有写操作返回新实例，避免共享可变状态。</p>
 */
public final class ComponentMap {

    public static final ComponentMap EMPTY = new ComponentMap(Map.of());

    private final Map<ComponentType<?>, Object> values;

    private ComponentMap(Map<ComponentType<?>, Object> values) {
        this.values = values;
    }

    public static ComponentMap of(Map<ComponentType<?>, Object> values) {
        return values.isEmpty() ? EMPTY : new ComponentMap(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    /** 取值；类型不匹配或不存在返回 null。 */
    @SuppressWarnings("unchecked")
    public <T> T get(ComponentType<T> type) {
        return (T) values.get(type);
    }

    /** 取值，缺失时回退组件自身的缺省值。 */
    public <T> T getOrDefault(ComponentType<T> type) {
        T v = get(type);
        return v != null ? v : type.defaultValue();
    }

    /** 取值，缺失时回退给定值。 */
    public <T> T getOrDefault(ComponentType<T> type, T fallback) {
        T v = get(type);
        return v != null ? v : fallback;
    }

    public boolean has(ComponentType<?> type) {
        return values.containsKey(type);
    }

    public Set<ComponentType<?>> keySet() {
        return values.keySet();
    }

    public int size() {
        return values.size();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** 返回带该组件的副本（原实例不变）。 */
    public <T> ComponentMap with(ComponentType<T> type, T value) {
        Map<ComponentType<?>, Object> copy = new LinkedHashMap<>(values);
        copy.put(type, value);
        return new ComponentMap(Collections.unmodifiableMap(copy));
    }

    /** 返回移除该组件后的副本。 */
    public ComponentMap without(ComponentType<?> type) {
        if (!values.containsKey(type)) return this;
        Map<ComponentType<?>, Object> copy = new LinkedHashMap<>(values);
        copy.remove(type);
        return copy.isEmpty() ? EMPTY : new ComponentMap(Collections.unmodifiableMap(copy));
    }

    @Override
    public String toString() {
        return "ComponentMap" + values;
    }
}
