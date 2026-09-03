package net.minecraft.client.yiz.tool.health;

/**
 * 真实生命值（Value Life）载体标记接口。
 *
 * <p>由把真值藏在每实体 {@link ValueLife} 容器里的实体类（下游 YizxianMob）实现，
 * 使前置库的 {@link SecureHealthClosure} 能经本接口统一读写真值容器，<b>而无需反向引用下游实体类</b>
 * （前置库 → 下游是单向依赖，编译期不能直接碰 YizxianMob）。</p>
 *
 * <p>实现方持有 <code>private final ValueLife valueLife = new ValueLife();</code>，
 * {@link #yizValueLife()} 返回该 final 引用锁死的实例。调用方拿到的永远是同一对象；
 * 真值经 {@link ValueLife#get}/{@link ValueLife#put} 读写（写入口调用栈鉴权）。</p>
 */
public interface LifeValueBearer {

    /** 返回本实体真值藏匿容器（final 引用锁死，调用方拿到的是同一实例）。 */
    ValueLife yizValueLife();
}
