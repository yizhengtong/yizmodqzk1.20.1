package net.minecraft.client.yiz.creature;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 组件增量补丁 —— 记录相对原型的「新增/覆盖」与「移除」。
 *
 * <p>对标 1.21 的 {@code DataComponentPatch}：原型给出生物的类级默认配置，
 * 补丁表达单个实例或数据包对它的差异（棋子星级、指令编辑、数据包覆盖）。</p>
 *
 * <p>不可变；写操作返回新实例。</p>
 */
public final class ComponentPatch {

    public static final ComponentPatch EMPTY = new ComponentPatch(Map.of(), Set.of());

    private final Map<ComponentType<?>, Object> added;
    private final Set<ComponentType<?>> removed;

    private ComponentPatch(Map<ComponentType<?>, Object> added, Set<ComponentType<?>> removed) {
        this.added = added;
        this.removed = removed;
    }

    public static ComponentPatch of(Map<ComponentType<?>, Object> added, Set<ComponentType<?>> removed) {
        if (added.isEmpty() && removed.isEmpty()) return EMPTY;
        return new ComponentPatch(
            Collections.unmodifiableMap(new LinkedHashMap<>(added)),
            Collections.unmodifiableSet(new LinkedHashSet<>(removed)));
    }

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty();
    }

    /** 返回带该覆盖的新补丁（原实例不变）。 */
    public <T> ComponentPatch with(ComponentType<T> type, T value) {
        Map<ComponentType<?>, Object> a = new LinkedHashMap<>(added);
        a.put(type, value);
        Set<ComponentType<?>> r = new LinkedHashSet<>(removed);
        r.remove(type);
        return of(a, r);
    }

    /** 返回标记移除该组件的新补丁。 */
    public ComponentPatch without(ComponentType<?> type) {
        Map<ComponentType<?>, Object> a = new LinkedHashMap<>(added);
        a.remove(type);
        Set<ComponentType<?>> r = new LinkedHashSet<>(removed);
        r.add(type);
        return of(a, r);
    }

    /** 合并另一个补丁（后者优先）。 */
    public ComponentPatch merge(ComponentPatch other) {
        if (other == null || other.isEmpty()) return this;
        if (this.isEmpty()) return other;
        Map<ComponentType<?>, Object> a = new LinkedHashMap<>(added);
        Set<ComponentType<?>> r = new LinkedHashSet<>(removed);
        other.removed.forEach(t -> {
            a.remove(t);
            r.add(t);
        });
        other.added.forEach((t, v) -> {
            a.put(t, v);
            r.remove(t);
        });
        return of(a, r);
    }

    /** 把本补丁应用到原型上，得到最终组件集合。 */
    public ComponentMap apply(ComponentMap base) {
        if (isEmpty()) return base;
        Map<ComponentType<?>, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            base.keySet().forEach(t -> merged.put(t, base.get(t)));
        }
        removed.forEach(merged::remove);
        merged.putAll(added);
        return ComponentMap.of(merged);
    }

    public boolean isRemoved(ComponentType<?> type) {
        return removed.contains(type);
    }

    public Map<ComponentType<?>, Object> added() {
        return added;
    }

    public Set<ComponentType<?>> removed() {
        return removed;
    }

    @Override
    public String toString() {
        return "ComponentPatch{added=" + added + ", removed=" + removed + "}";
    }
}
