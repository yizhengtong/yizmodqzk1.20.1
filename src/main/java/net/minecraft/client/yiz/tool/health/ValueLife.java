package net.minecraft.client.yiz.tool.health;

/**
 * 每实体真实生命值（Value Life）藏匿单元。
 *
 * <p>持有方（实现 {@link LifeValueBearer} 的实体）以
 * <code>private final ValueLife valueLife = new ValueLife();</code> 持有本对象：
 * <ul>
 *   <li><b>final 引用锁死</b>：字段声明即赋值，此后 valueLife 永远指向这一个实例。
 *       外部反射无法把持有方字段改指向自己构造的容器（普通反射改不了 final 实例字段值），
 *       杜绝「替换引用 / 制造平行血量源」式篡改；</li>
 *   <li><b>写入口鉴权</b>：{@link #put} 内部做调用栈鉴权（家族包 / 引擎帧放行），
 *       外部即使反射拿到容器对象直接 put 也会被拒——真值写入只能经本模组收口路径；</li>
 *   <li><b>对象内容可变</b>：真值仍可经 {@link #put} 放入、经 {@link #get} 取出。</li>
 * </ul>
 *
 * <p>定位：真实血量存储的<b>更底层藏匿层</b>，真值沉到实体自己的实例对象里。
 * 上层（DataParameter 混淆串）保留为存档 + 客户端同步载体，服务端每 tick 从本容器回写。</p>
 *
 * <p><b>初始值 NaN 语义</b>：服务端实体注册（registerSecureHealth）前、以及客户端实体
 * （不参与权威，值不落位）均保持 NaN——读侧用 {@link Float#isNaN} 区分「未注册」与「真 0 死亡」，
 * 避免把未初始化误判为死亡 / 把客户端空容器当 0 血。</p>
 */
public final class ValueLife {

    /** 真值。NaN = 未注册（服务端首 tick 前 / 客户端实例）；藏匿粒度后续可加深（混淆 / 字段拆分）。 */
    private float value = Float.NaN;

    /** 读真值。读入口无鉴权（热路径）。未注册返回 NaN。 */
    public float get() {
        return value;
    }

    /** 是否已有有效真值（非 NaN）。 */
    public boolean isSet() {
        return !Float.isNaN(value);
    }

    /** 写真值：调用栈鉴权，非信任调用方被拒（外部直改容器内容无效）；NaN 归一不落位。 */
    public boolean put(float v) {
        if (Float.isNaN(v)) return false;
        if (!net.minecraft.client.yiz.tool.attribute.EntityAttributeGate.isCallerTrusted()) {
            net.minecraft.client.yiz.tizMod.LOGGER.warn("[ValueLife] 拒绝非受信任调用方写真值 {}（外部直改藏匿容器被拦）", v);
            return false;
        }
        this.value = v;
        return true;
    }
}
