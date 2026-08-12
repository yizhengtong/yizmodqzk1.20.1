package net.minecraft.client.yiz.tool.health;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实血量表存储（隐藏类载体）。
 *
 * <p> <b>本类不会被普通加载</b>——{@link SecureHealthClosure} 只读取本类的 .class 字节，
 * 通过 {@link java.lang.invoke.MethodHandles.Lookup#defineHiddenClass} 定义成 <b>隐藏类</b>，
 * 再经 MethodHandle 取静态字段。外部 mod 无法按名字 Class.forName/反射/点名 transform 它，
 * 也就无法绕过 setHealth 鉴权直接写血量表。</p>
 *
 * <p>字段均为 <code>public static final</code> Map——外部即使通过 Unsafe 全堆扫描定位到
 * 持有 Map 的隐藏类，也因字段被隐藏类封装而难以稳定访问（把"点名直打"抬到"全堆扫描+反射猜字段"）。</p>
 */
public class HealthVault {

    /** 真实血量表：UUID → 逻辑血量（明文 float）。 */
    public static final ConcurrentHashMap<UUID, Float> HEALTH_MAP = new ConcurrentHashMap<>();

    /** 受保护最大生命表：UUID → maxHealth。 */
    public static final ConcurrentHashMap<UUID, Float> MAX_HEALTH_MAP = new ConcurrentHashMap<>();

    private HealthVault() {}
}
