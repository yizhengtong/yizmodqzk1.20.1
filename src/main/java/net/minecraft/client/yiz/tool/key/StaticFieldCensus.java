package net.minecraft.client.yiz.tool.key;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 按「字段类型」普查目标类静态字段 —— KeyHunter 四步配方的第 2 步。
 *
 * <p><b>只认类型、不看名字</b>（字段名可混淆，类型改不了）：</p>
 * <ul>
 *   <li>{@code byte[]} / 密钥形态 {@code String} / {@code UUID} → 密钥候选（④ 支柱①）；</li>
 *   <li>{@code ThreadLocal} → 会话握手点（支柱③）；</li>
 *   <li>{@code StackWalker} → 调用栈鉴权闸门（支柱②，仅对「闸门样」类生效防误伤）；</li>
 *   <li>{@code String[]} → 包前缀白名单数组候选（支柱②，可扩展）。</li>
 * </ul>
 * <p>所有值读取均走 {@link FieldHandle}（Unsafe，无反射帧），private/final 无视。</p>
 */
public final class StaticFieldCensus {

    /** 密钥形态 String 过滤：hex / base64 / uuid 之类长 token。 */
    private static final Pattern KEY_STRING = Pattern.compile("^[A-Za-z0-9+/=_-]{16,}$");
    private static final Pattern UUID_STRING = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern PACKAGE_PREFIX = Pattern.compile("^[a-z_][a-zA-Z0-9_$]*(\\.[a-zA-Z0-9_$]+)*\\.$");

    /** 闸门样类名弱特征（配合 String[] 白名单强特征一起用，降低 StackWalker 置换的误伤率）。 */
    private static final Pattern GATE_LIKE_NAME = Pattern.compile(
            "(?i).*(check|guard|access|secure|stack|gate|auth|anticheat|verify).*");

    /** 密钥候选。 */
    public record KeyCandidate(FieldHandle handle, String kind, byte[] rawBytes, String textValue,
                               long longValue, int strength, double entropy) {
        public String preview() {
            if (rawBytes != null) return "byte[" + rawBytes.length + "] " + hexPreview(rawBytes, 48);
            if (textValue != null) return "\"" + textValue + "\"";
            return "0x" + Long.toHexString(longValue);
        }
    }

    /** 调用栈鉴权闸门（静态 StackWalker 字段）。StackWalker 为 final 类，运行时不可置换
     *  实例——只做检测报告，walk/getCallerClass 型判定的中和走字节码层调用点改写。 */
    public record GateField(FieldHandle handle, Object currentWalker) {}

    /** 包前缀白名单数组候选。 */
    public record WhitelistField(FieldHandle handle, String[] entries) {}

    /** ThreadLocal 会话握手点。 */
    public record HandshakeField(FieldHandle handle, ThreadLocal<?> threadLocal) {}

    /** 普查汇总。 */
    public record CensusResult(List<KeyCandidate> keys, List<GateField> gates,
                               List<WhitelistField> whitelists, List<HandshakeField> handshakes,
                               int classesScanned, int classesFailed) {}

    private StaticFieldCensus() {}

    public static CensusResult scan(Collection<Class<?>> classes) {
        List<KeyCandidate> keys = new ArrayList<>();
        List<GateField> gates = new ArrayList<>();
        List<WhitelistField> whitelists = new ArrayList<>();
        List<HandshakeField> handshakes = new ArrayList<>();
        int scanned = 0, failed = 0;

        for (Class<?> clazz : classes) {
            try {
                List<Field> declared = List.of(clazz.getDeclaredFields());
                boolean gateLike = isGateLikeClass(clazz, declared);
                for (Field f : declared) {
                    if (f.isSynthetic() || !Modifier.isStatic(f.getModifiers())) continue;
                    FieldHandle h = FieldHandle.of(f);
                    if (h == null) continue;
                    Class<?> type = f.getType();
                    try {
                        if (type == byte[].class) {
                            byte[] v = (byte[]) h.tryGetObject();
                            if (v != null && v.length >= 8) {
                                keys.add(new KeyCandidate(h, "byte[]", v, null, 0,
                                        Math.min(v.length, 128), shannonEntropy(v)));
                            }
                        } else if (type == String.class) {
                            Object v = h.tryGetObject();
                            if (v instanceof String s && (KEY_STRING.matcher(s).matches()
                                    || UUID_STRING.matcher(s).matches())) {
                                keys.add(new KeyCandidate(h, "String", null, s, 0,
                                        Math.min(s.length(), 64), shannonEntropy(s)));
                            }
                        } else if (type == UUID.class) {
                            Object v = h.tryGetObject();
                            if (v instanceof UUID u) {
                                keys.add(new KeyCandidate(h, "UUID", null, u.toString(), 0, 36, 0));
                            }
                        } else if (type == ThreadLocal.class) {
                            Object v = h.tryGetObject();
                            if (v instanceof ThreadLocal<?> tl) handshakes.add(new HandshakeField(h, tl));
                        } else if (type == StackWalker.class) {
                            Object v = h.tryGetObject();
                            if (gateLike && v != null) gates.add(new GateField(h, v));
                        } else if (type == String[].class) {
                            Object v = h.tryGetObject();
                            if (v instanceof String[] arr && looksLikeWhitelist(arr)) {
                                whitelists.add(new WhitelistField(h, arr));
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                scanned++;
            } catch (Throwable t) {
                failed++;
            }
        }
        return new CensusResult(keys, gates, whitelists, handshakes, scanned, failed);
    }

    /** 闸门样类：含包前缀形 String[] 白名单（强特征），或类名含 check/guard/access…（弱特征）。 */
    private static boolean isGateLikeClass(Class<?> clazz, List<Field> fields) {
        if (GATE_LIKE_NAME.matcher(clazz.getSimpleName()).matches()) return true;
        for (Field f : fields) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() == String[].class) {
                FieldHandle h = FieldHandle.of(f);
                if (h != null) {
                    Object v = h.tryGetObject();
                    if (v instanceof String[] arr && looksLikeWhitelist(arr)) return true;
                }
            }
        }
        return false;
    }

    /** 数组形似包前缀白名单：非空且 ≥50% 元素形如 {@code xxx.}。 */
    private static boolean looksLikeWhitelist(String[] arr) {
        if (arr == null || arr.length == 0) return false;
        int hits = 0;
        for (String s : arr) {
            if (s != null && PACKAGE_PREFIX.matcher(s).matches()) hits++;
        }
        return hits * 2 >= arr.length;
    }

    /** 香农熵（0..8 bit/字节）估算字节数组随机性；字符串按字符估算后换算。 */
    public static double shannonEntropy(byte[] data) {
        if (data == null || data.length == 0) return 0;
        int[] freq = new int[256];
        for (byte b : data) freq[b & 0xFF]++;
        double h = 0;
        double n = data.length;
        for (int f : freq) {
            if (f == 0) continue;
            double p = f / n;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    public static double shannonEntropy(String s) {
        if (s == null || s.isEmpty()) return 0;
        return shannonEntropy(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String hexPreview(byte[] data, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(data.length, maxBytes);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.ROOT, "%02x", data[i]));
        }
        if (data.length > maxBytes) sb.append("…");
        return sb.toString();
    }
}
