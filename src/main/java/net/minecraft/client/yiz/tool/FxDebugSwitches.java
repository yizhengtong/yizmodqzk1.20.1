package net.minecraft.client.yiz.tool;

import net.minecraft.client.yiz.lightning.render.LightningRenderer;

/**
 * <b>【特效开关】</b>闪电特效只剩两项 —— 用户逐项实测（{@code /yiz fxtoggle 1..9}）后的定案：
 * <b>真正有用的只有 1 号「闪电链」与 4 号「围弧」</b>，其余（2/3/5/6/7/8/9）全部删除。
 *
 * <p><b>编号只保留 1 与 4，且语义不变</b>（用户已用这套编号做过实测，不重排）：</p>
 * <ol>
 *   <li>{@code 1 = 闪电链}（<b>默认开</b>）：两实体之间的链式电弧（{@link LightningRenderer} 的 {@code ARCS}）。
 *       生成入口 = {@code LightningFX.spawnArc} 的两个重载 + {@code S2CShockFxPayload} kind=1；
 *       渲染入口 = {@code LightningRenderer.renderAll} 的链式段。
 *       关掉时 {@link LightningRenderer#clearArcs()} 清掉已在飞的弧。</li>
 *   <li>{@code 4 = 围弧}（<b>默认开</b>）：感电期间<b>飘在实体身体外那一圈空间里</b>跳动闪烁的短电弧
 *       （<b>不是</b>贴在皮肤上的图案 —— 第六轮换了几何，见 {@code ShockArcs} 类注释）。
 *       生成入口 = {@code S2CShockFxPayload} kind=0 分支（{@code ShockEffects.shock(entity, ticks)}）
 *       与 {@code LightningEmitter#fire}（命中瞬间 0.35s / 强度 1.25）；
 *       渲染入口 = {@code LightningRenderer} 的 pass（与编号 1 同一个 BufferBuilder/着色器/状态），
 *       几何与端点由 {@code ShockArcs} 提供（端点取自模型顶点采出的锚点）。
 *       关掉时清状态表与弧群（{@code ShockEffects.clear()} + {@code ShockArcs.clear()}）。</li>
 * </ol>
 * <p>其它编号一律回一句"该表现已删除"，不再是"未知编号"（用户就用这些编号测过，得给个明确答复）。
 * 槽位常量 {@link #CHAIN_ARC} / {@link #SURFACE_ARCS} 故意保留 1 与 4 这两个数值，
 * 就是为了让回显里的编号与用户实测时看到的完全一致。</p>
 *
 * <h2>⚑ 已删除清单（别再接线回来）</h2>
 * <ul>
 *   <li><b>2 / 3</b>：模型几何镜像的 {@code LIGHTNING} / {@code AIR_ARC} 层（"贴模型"的两种做法）——
 *       用户实测<b>都是一开（有实体感电时）就整屏发黑</b>。层、状态表、生成入口、着色器资源
 *       （{@code rendertype_lightning_surface.*} / {@code rendertype_lightning_modelarc.*}）全部删除。
 *       黑屏根因收敛到两条共因候选（着色器 JSON 缺 {@code "blend"} 节点 ⇒ 默认 {@code opaque=true}
 *       ⇒ 着色器程序自己 {@code RenderSystem.disableBlend()}；以及用 {@code setShaderColor}
 *       当强度通道包住 {@code flush()}），写在 {@code LightningRenderer} 的类注释里 ——
 *       <b>恢复"贴模型闪电"之前必须先解决那一族问题，且不要再用闪电着色器走镜像管线</b>。</li>
 *   <li><b>5</b>：旧体表叠亮（{@code LivingEntityRendererSurfaceLightningMixin} 把模型整只重画一遍）+
 *       其着色器 {@code rendertype_lightning_surface.*}。</li>
 *   <li><b>6</b>：球状闪电（{@code LightningFX.spawnBall} / {@code BallEffect} / {@code BallGeometry} /
 *       ball 着色器）。</li>
 *   <li><b>7</b>：电弧火花粒子（{@code SparkSpawner} / {@code SparkEffect} / {@code SparkGeometry} /
 *       {@code SparkConfig} / spark 着色器）。</li>
 *   <li><b>8</b>：球面游离弧（{@code spawnSurfaceArcOnBall}）。</li>
 *   <li><b>9</b>：被动带电指示的<b>判定</b>（{@code LightningSurfaceState}）—— 它只喂给已删除的 5 号。
 *       {@code SHOCK_ATTACK} / {@code SHOCK_DEFENSE} <b>属性本身保留</b>（注册在 {@code tizMod}，别动）。</li>
 *   <li>连带死代码：{@code ShockedEntityAPI}（感电状态机，唯一消费者 5 号）与 {@code LightningFX.skillA/B/C}。</li>
 * </ul>
 *
 * <h2>硬性验收：{@code all off} 之后一个闪电都不剩</h2>
 * <p>绘制路径现在只有 2 条，各有一道生成闸门 + 一道渲染段兜底：</p>
 * <ol>
 *   <li>链式电弧：{@code LightningFX.spawnArc}×2 与 {@code S2CShockFxPayload} kind=1（生成）
 *       + {@code renderAll} 链式段（渲染）；</li>
 *   <li>围弧：{@code S2CShockFxPayload} kind=0 与 {@code LightningEmitter#fire}（生成）
 *       + {@code renderAll} 围弧段（渲染）。</li>
 * </ol>
 * <p>渲染段兜底的意义：即便有谁绕过生成入口直接往列表里塞（{@code LightningRenderer.enqueue*} 是 public），
 * 也画不出来。</p>
 * <p>注：<b>贴模型着火</b>（{@code FLAME} 镜像层）与<b>描边</b>（{@code LockOutline*}）不属于"闪电/电流"，
 * 由它们自己的入口控制，本组开关刻意不碰 —— {@code all off} 之后着火与描边照旧。</p>
 *
 * <p><b>客户端内存态</b>：只改本进程的静态布尔，<b>不做网络同步、不写盘</b>，重进游戏即恢复默认。
 * 整合服务器与客户端同 JVM，所以服务端指令（{@code /yiz fxtoggle}）能直接切到客户端渲染线程读到的值。</p>
 */
public final class FxDebugSwitches {

    /** 槽位 1 = 闪电链（两实体之间的链式电弧，{@code ARCS}）。数值 1 是用户实测用的编号，勿改。 */
    public static final int CHAIN_ARC = 1;
    /** 槽位 4 = 围弧（感电期间飘在实体身体外那一圈空间里的短弧群）。数值 4 同上，勿改。 */
    public static final int SURFACE_ARCS = 4;

    /** 最大槽位号（指令 tab 补全与"已删除"回显用）。 */
    public static final int MAX_SLOT = SURFACE_ARCS;

    /**
     * 渲染线程每帧读、指令线程写 → 一律 volatile。
     *
     * <p><b>默认值</b>：两项都<b>开</b> —— 用户确认有用的就是这两项。</p>
     */
    private static volatile boolean chainArc = true;
    /** 槽位 4 = 围弧（<b>默认开</b>：用户要的就是这种"围着实体跳的小弧"，现在飘在体外、且仍然跟随模型）。 */
    private static volatile boolean surfaceArcs = true;

    private FxDebugSwitches() {}

    // ══════════════════════════════════════════════════════════════════
    //  读取点（渲染线程每帧查）
    // ══════════════════════════════════════════════════════════════════

    /** 1 = 闪电链（{@code LightningFX.spawnArc} 两个重载 + {@code S2CShockFxPayload} kind=1）。 */
    public static boolean chainArc() {
        return chainArc;
    }

    /**
     * 4 = 围弧（{@code S2CShockFxPayload} kind=0 与 {@code LightningEmitter#fire} 的登记
     * + 锚点采集 + {@code ShockArcs} 的绘制，全部由本开关把关）。
     *
     * <p><b>这个开关是围弧的总闸</b>：关掉后 ① 生成入口不再登记状态；
     * ② 锚点采集立刻停（{@code ShockArcs.beginFrame} 直接返回 null ⇒ 连顶点都不会被额外收走）；
     * ③ 已有的弧在下一次切换时被 {@code ShockArcs.clear()} 清空；
     * ④ 渲染段的 {@code surfaceArcs()} 兜底闸门也挡住。四条一起 ⇒ "完全不画"立刻生效。
     * <b>默认开</b>。</p>
     */
    public static boolean surfaceArcs() {
        return surfaceArcs;
    }

    // ══════════════════════════════════════════════════════════════════
    //  切换
    // ══════════════════════════════════════════════════════════════════

    /**
     * 切换一个槽位；<b>关掉时顺手把"已经在飞/已经在跳"的实例清掉</b>（不留半成品残影）。
     *
     * <p>1 号清 {@link LightningRenderer#clearArcs()}，4 号清
     * {@code ShockEffects.clear()}（围弧的状态表）—— 两者各管各的。</p>
     *
     * @return 切换后的状态；<b>槽位号不是 1 或 4</b>（含已删除的 2/3/5/6/7/8/9）返回 {@code null}
     */
    public static Boolean toggle(int slot) {
        switch (slot) {
            case CHAIN_ARC -> {
                chainArc = !chainArc;
                if (!chainArc) LightningRenderer.clearArcs();
                return chainArc;
            }
            case SURFACE_ARCS -> {
                surfaceArcs = !surfaceArcs;
                // 围弧（编号 4）= 状态表（谁在感电）+ 弧群（ShockArcs）+ 锚点采集。
                // 关掉时两处一起清：状态没了 ⇒ 下一个 tick 起锚点采集就停、已有的弧也全部回收
                // （ShockArcs.tick 见到状态消失就把整条 Track 丢掉）⇒ "完全不画"立刻生效。
                if (!surfaceArcs) {
                    net.minecraft.client.yiz.lightning.fx.ShockEffects.clear();
                    net.minecraft.client.yiz.lightning.fx.ShockArcs.clear();
                }
                return surfaceArcs;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 一次全开 / 全关（快速复位用）。
     *
     * <p>{@code off} 时把两条通道一次清空：链式电弧的队列（{@link LightningRenderer#clear()}）
     * 与围弧的状态表（{@code ShockEffects.clear()}）⇒ 同帧起屏幕上不再有闪电。
     * 刻意<b>不动</b> {@code EMITTERS} 的产物之外的东西（编排器本身不画东西，它的产物分别被 1 / 4 挡住）。</p>
     */
    public static void setAll(boolean on) {
        chainArc = on;
        surfaceArcs = on;
        if (!on) {
            LightningRenderer.clear();                              // ARCS（+ EMITTERS）
            net.minecraft.client.yiz.lightning.fx.ShockEffects.clear(); // 围弧状态表（编号 4）
            net.minecraft.client.yiz.lightning.fx.ShockArcs.clear();    // 围弧弧群 + 锚点表（编号 4）
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  回显（日志 + 聊天栏）
    // ══════════════════════════════════════════════════════════════════

    /** 纯文本状态（日志用）：{@code 1闪电链=开 4围弧=开}。 */
    public static String plainStatus() {
        return "1" + name(CHAIN_ARC) + "=" + onOff(chainArc)
                + " 4" + name(SURFACE_ARCS) + "=" + onOff(surfaceArcs);
    }

    /** 聊天栏状态（带颜色）：{@code §b1闪电链=§a开 §b4围弧=§a开}。 */
    public static String coloredStatus() {
        return "§b1" + name(CHAIN_ARC) + "=" + colored(chainArc)
                + "  §b4" + name(SURFACE_ARCS) + "=" + colored(surfaceArcs);
    }

    /**
     * 状态行（{@code /yiz fxtoggle status} 用）。
     *
     * <p><b>只有一项</b>：用户要求 {@code status} 就只剩 1 与 4 两项，两行分开写最清楚
     * （合一行会跟切换回显挤在一起看不清）。</p>
     */
    public static String[] statusRows() {
        return new String[] {
                "§b1 " + name(CHAIN_ARC) + "§7=§r" + colored(chainArc),
                "§b4 " + name(SURFACE_ARCS) + "§7=§r" + colored(surfaceArcs)
        };
    }

    /** 槽位号 → 中文名（回显用）。 */
    public static String name(int slot) {
        return switch (slot) {
            case CHAIN_ARC -> "闪电链";
            case SURFACE_ARCS -> "围弧(跟随模型)";
            default -> "未知(" + slot + ")";
        };
    }

    /**
     * 槽位号 → 当前状态：{@code true/false} = 该表现的开/关；
     * <b>{@code null} = 不是 1 / 4</b>（已删除的那些编号也走这里）。
     */
    public static Boolean stateOf(int slot) {
        return switch (slot) {
            case CHAIN_ARC -> chainArc;
            case SURFACE_ARCS -> surfaceArcs;
            default -> null;
        };
    }

    /** 该槽位是否"已删除"（凡不是 1 / 4 的编号）：指令层据此给出"该表现已删除"的回显。 */
    public static boolean isRemoved(int slot) {
        return slot != CHAIN_ARC && slot != SURFACE_ARCS;
    }

    private static String onOff(boolean on) {
        return on ? "开" : "关";
    }

    private static String colored(boolean on) {
        return on ? "§a开" : "§c关";
    }
}
