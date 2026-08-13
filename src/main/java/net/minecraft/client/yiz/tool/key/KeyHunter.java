package net.minecraft.client.yiz.tool.key;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 通用「内部 Key 判定」攻取门面 —— 四步配方一次跑完：
 *
 * <ol>
 *   <li><b>枚举</b>：{@link LoadedClassEnumerator} 按包前缀枚举目标 jar 已加载类；</li>
 *   <li><b>定位</b>：{@link StaticFieldCensus} 按类型普查密钥/闸门/白名单/握手点；</li>
 *   <li><b>夺取</b>：Unsafe 直读密钥字段（含字节码层比较点捕获的密钥）；</li>
 *   <li><b>通行</b>：{@link StackGateNeutralizer} 白名单扩展 + {@link HandshakeForger}
 *       握手伪造。StackWalker 是 final 类，运行时层对 walk 型闸门只能检测（报告提示
 *       走字节码层 {@code /yiz key watch} 调用点改写）。</li>
 * </ol>
 *
 * <p>全程不引用任何目标模组的类名/字段名/方法名 —— 只认类型特征与可配置包前缀。</p>
 */
public final class KeyHunter {

    /** 攻取报告。 */
    public static final class KeyHuntReport {
        public final List<String> prefixes;
        public final int classesScanned;
        public final int classesFailed;
        public final String enumerationSource;
        public final int loadersInspected;
        public final int totalClassesSeen;
        public final boolean agentActive;
        public final List<StaticFieldCensus.KeyCandidate> candidates;
        public final List<StaticFieldCensus.KeyCandidate> capturedCandidates;
        public final int gatesDetected;
        public final int whitelistsExtended;
        public final List<StaticFieldCensus.HandshakeField> handshakes;
        public final byte[] chosenKey;
        public final String chosenKeySource;

        KeyHuntReport(List<String> prefixes, int classesScanned, int classesFailed,
                      String enumerationSource, int loadersInspected, int totalClassesSeen,
                      boolean agentActive,
                      List<StaticFieldCensus.KeyCandidate> candidates,
                      List<StaticFieldCensus.KeyCandidate> capturedCandidates,
                      int gatesDetected, int whitelistsExtended,
                      List<StaticFieldCensus.HandshakeField> handshakes,
                      byte[] chosenKey, String chosenKeySource) {
            this.prefixes = prefixes;
            this.classesScanned = classesScanned;
            this.classesFailed = classesFailed;
            this.enumerationSource = enumerationSource;
            this.loadersInspected = loadersInspected;
            this.totalClassesSeen = totalClassesSeen;
            this.agentActive = agentActive;
            this.candidates = candidates;
            this.capturedCandidates = capturedCandidates;
            this.gatesDetected = gatesDetected;
            this.whitelistsExtended = whitelistsExtended;
            this.handshakes = handshakes;
            this.chosenKey = chosenKey;
            this.chosenKeySource = chosenKeySource;
        }

        public boolean hasKey() { return chosenKey != null; }
        public boolean hasHandshakes() { return !handshakes.isEmpty(); }

        /** 是否已具备「以特权身份通行」的条件：有密钥 + 有握手点（或白名单已扩展）。 */
        public boolean accessReady() {
            return hasKey() && (hasHandshakes() || whitelistsExtended > 0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("§e=== /yiz key 攻取报告 ===\n");
            sb.append("§7[枚举] 前缀: §f").append(String.join(", ", prefixes)).append("\n");
            sb.append("§7[枚举] 已扫描类: §f").append(classesScanned)
                .append("§7 失败: §f").append(classesFailed)
                .append("§7 | 来源: §f").append(enumerationSource)
                .append("§7 | agent: §f").append(agentActive ? "§a已挂载" : "§c未挂载").append("\n");
            if (loadersInspected > 0) {
                sb.append("§7[枚举] 检查加载器: §f").append(loadersInspected)
                    .append("§7 | 可见类总数: §f").append(totalClassesSeen).append("\n");
            }
            if (classesScanned == 0) {
                sb.append("§c[诊断] 前缀下 0 类。可能原因:\n");
                sb.append("§7  1) 目标模组未安装/未初始化——确认 mods 目录有该 jar 且进过世界;\n");
                sb.append("§7  2) agent 未挂载且 ModList 枚举不可用——/yiz agent 查看状态，")
                    .append("PCL 需加 JVM 参数 --add-modules=jdk.attach -Djdk.attach.allowAttachSelf=true;\n");
                sb.append("§7  3) 前缀写错——包名区分大小写（如 flashfur.omnimobs）。\n");
            }
            sb.append("§7[定位] 密钥候选: §f").append(candidates.size())
                .append("§7 | 捕获密钥: §f").append(capturedCandidates.size())
                .append("§7 | 握手点: §f").append(handshakes.size())
                .append("§7 | StackWalker 闸门: §f").append(gatesDetected)
                .append("§7 | 白名单扩展: §f").append(whitelistsExtended).append("\n");
            for (StaticFieldCensus.KeyCandidate k : candidates) {
                sb.append("§b  [密钥] ").append(k.handle().describe()).append("\n");
                sb.append("§7     ").append(k.kind()).append(" 强度=").append(k.strength())
                    .append(" 熵=").append(String.format("%.2f", k.entropy()))
                    .append(" 值=").append(k.preview()).append("\n");
            }
            for (StaticFieldCensus.KeyCandidate k : capturedCandidates) {
                sb.append("§d  [捕获] ").append(k.handle() == null ? "(比较点)" : k.handle().describe()).append("\n");
                sb.append("§7     ").append(k.kind()).append(" 值=").append(k.preview()).append("\n");
            }
            for (StaticFieldCensus.HandshakeField h : handshakes) {
                sb.append("§a  [握手] ").append(h.handle().describe()).append("\n");
            }
            if (gatesDetected > 0) {
                sb.append("§6  [提示] 检测到 ").append(gatesDetected)
                    .append(" 个静态 StackWalker 闸门字段（final 类，运行时不可置换）——")
                    .append("walk/getCallerClass 型判定请用 /yiz key watch 做调用点改写\n");
            }
            if (chosenKey != null) {
                sb.append("§2[夺取] 选定密钥: ").append(chosenKeySource).append(" byte[")
                    .append(chosenKey.length).append("]\n");
                sb.append(accessReady()
                    ? "§a[判定] 攻取就绪：可直接调用（无 reflect/invoke 帧）+ runWithForgedAccess 握手"
                    : "§e[判定] 部分就绪：有密钥但无握手点/白名单——需字节码层 watch 辅助");
            } else {
                sb.append("§c[判定] 未夺取到密钥——用 /yiz key watch 开比较点转储后再试");
            }
            return sb.toString();
        }
    }

    private KeyHunter() {}

    /** 对给定包前缀执行完整四步攻取。 */
    public static KeyHuntReport hunt(String... packagePrefixes) {
        List<String> prefixes = normalize(packagePrefixes);

        // 第 1 步：枚举（含诊断：来源 / 加载器数 / 可见类总数 / agent 状态）
        LoadedClassEnumerator.EnumerationResult er =
                LoadedClassEnumerator.classesIn(prefixes.toArray(new String[0]));
        List<Class<?>> classes = er.classes;
        boolean agentActive;
        try {
            agentActive = net.minecraft.client.yiz.core.asm.AgentBridge.isAgentActive();
        } catch (Throwable ignored) {
            agentActive = false;
        }

        // 第 2 步：定位
        StaticFieldCensus.CensusResult census = StaticFieldCensus.scan(classes);

        // 第 4a 步：白名单扩展（让我们的直接调用帧合法）
        int whitelistsExtended = StackGateNeutralizer.extendWhitelists(census.whitelists());

        // 第 3 步：夺取——合并「字段密钥候选」+「字节码层比较点捕获」
        List<StaticFieldCensus.KeyCandidate> captured = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : KeyDumpBridge.capturedBytes().entrySet()) {
            captured.add(new StaticFieldCensus.KeyCandidate(null, "byte[]", e.getValue(), null, 0,
                    Math.min(e.getValue().length, 128), StaticFieldCensus.shannonEntropy(e.getValue())));
        }
        for (Map.Entry<String, String> e : KeyDumpBridge.capturedText().entrySet()) {
            captured.add(new StaticFieldCensus.KeyCandidate(null, "String", null, e.getValue(), 0,
                    Math.min(e.getValue().length(), 64), StaticFieldCensus.shannonEntropy(e.getValue())));
        }

        // 选定最强密钥：优先「静态 byte[] 字段」里最长者（会话密钥几乎总是它），其次捕获
        StaticFieldCensus.KeyCandidate chosen = null;
        String chosenSource = null;
        for (StaticFieldCensus.KeyCandidate k : census.keys()) {
            if (k.rawBytes() == null) continue;
            if (chosen == null || k.rawBytes().length > (chosen.rawBytes() == null ? 0 : chosen.rawBytes().length)) {
                chosen = k;
                chosenSource = k.handle().describe();
            }
        }
        if (chosen == null) {
            for (StaticFieldCensus.KeyCandidate k : captured) {
                if (k.rawBytes() == null) continue;
                if (chosen == null || k.rawBytes().length > (chosen.rawBytes() == null ? 0 : chosen.rawBytes().length)) {
                    chosen = k;
                    chosenSource = "比较点捕获";
                }
            }
        }
        byte[] chosenKey = chosen == null ? null : chosen.rawBytes();

        return new KeyHuntReport(prefixes, census.classesScanned(), census.classesFailed(),
                er.source, er.loadersInspected, er.totalClassesSeen, agentActive,
                census.keys(), captured, census.gates().size(), whitelistsExtended,
                census.handshakes(), chosenKey, chosenSource);
    }

    /** 在伪造的会话令牌下执行 r（四步配方的最终消费入口；调用必须走直接调用，勿用反射）。 */
    public static void runWithForgedAccess(KeyHuntReport report, Runnable r) {
        if (report == null || r == null) return;
        if (!report.hasKey()) return;
        new HandshakeForger(report.handshakes, report.chosenKey).forge(r);
    }

    private static List<String> normalize(String[] prefixes) {
        List<String> out = new ArrayList<>();
        if (prefixes == null) return out;
        for (String p : prefixes) {
            if (p == null || p.isBlank()) continue;
            String trimmed = p.trim();
            // 拒绝本家族/游戏/平台前缀，防自注入
            if (trimmed.startsWith("net.minecraft.client.yiz")
                    || trimmed.startsWith("net.minecraft.")
                    || trimmed.startsWith("java.")
                    || trimmed.startsWith("jdk.")
                    || trimmed.startsWith("sun.")
                    || trimmed.startsWith("com.mojang.")
                    || trimmed.startsWith("org.spongepowered.")) {
                continue;
            }
            out.add(trimmed);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
