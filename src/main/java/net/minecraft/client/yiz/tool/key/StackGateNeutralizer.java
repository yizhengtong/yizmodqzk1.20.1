package net.minecraft.client.yiz.tool.key;

import java.util.Arrays;
import java.util.List;

/**
 * 调用栈鉴权闸门数据层中和器 —— KeyHunter 四步配方的第 4 步（闸门侧，运行时层）。
 *
 * <p><b>为什么不做 StackWalker 字段置换</b>：{@code java.lang.StackWalker} 是 <b>final</b> 类
 * （javap 确认 {@code public final class java.lang.StackWalker}），无法子类化出「空帧 Walker」，
 * 而任何真实 StackWalker 实例都会走真栈。因此运行时层只做白名单数据扩展；对 walk/getCallerClass
 * 型闸门的彻底中和走<b>字节码层</b>（{@code KeyCompareDumpTransformer} 的调用点改写）。</p>
 *
 * <p>运行时层手段：目标静态 {@code String[]} 白名单追加本家族包根
 * {@code net.minecraft.client.yiz.}，配合「直接调用（栈上无 reflect/invoke 帧）+
 * ThreadLocal 握手伪造」，我们的调用帧即变合法。覆写静态字段走 Unsafe（无视 private/final），
 * 目标方法体原样未动。</p>
 */
public final class StackGateNeutralizer {

    /** 额外注入白名单的包前缀（本家族包根，与 EntityAttributeGate 的调用栈鉴权同根）。 */
    public static final String EXTRA_PACKAGE = "net.minecraft.client.yiz.";

    private StackGateNeutralizer() {}

    /** 向所有白名单数组候选追加 EXTRA_PACKAGE，返回成功数。 */
    public static int extendWhitelists(List<StaticFieldCensus.WhitelistField> whitelists) {
        return extendWhitelists(whitelists, EXTRA_PACKAGE);
    }

    public static int extendWhitelists(List<StaticFieldCensus.WhitelistField> whitelists, String extraPrefix) {
        if (whitelists == null || extraPrefix == null) return 0;
        int ok = 0;
        for (StaticFieldCensus.WhitelistField wl : whitelists) {
            FieldHandle h = wl.handle();
            String[] current = wl.entries();
            if (current == null) continue;
            boolean already = false;
            for (String e : current) {
                if (extraPrefix.equals(e)) {
                    already = true;
                    break;
                }
            }
            if (already) {
                ok++;
                continue;
            }
            String[] extended = Arrays.copyOf(current, current.length + 1);
            extended[current.length] = extraPrefix;
            if (h.tryPutObject(extended)) ok++;
        }
        return ok;
    }
}
