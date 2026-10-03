package net.minecraft.client.yiz.modelmirror;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 模型几何镜像（贴模型火焰 / 体表闪电<b>共用</b>）的<b>临时</b>诊断工具：只做"限流"这一件事，
 * 日志本身写在各自的调用点上（贴模型火焰写 {@code [FlameSurface]}，体表闪电写 {@code [LightningSurface]}，
 * 镜像机制自己写 {@code [ModelMirror]}）。
 *
 * <p>为什么要它：镜像链路横跨"状态建立 → mixin 换缓冲 → 模型顶点双写 → AFTER_ENTITIES 刷帧"四段，
 * 任何一段静默退出都表现为"完全看不到效果"，而每帧每实体都打日志既刷屏又没法读。
 * {@link #first(String)} 让每个 key 只放行第一条日志，于是"最关键的第一次"永远打得出来，总量可控。</p>
 *
 * <p><b>key 必须带消费者前缀</b>（{@code "flame:draw:"} / {@code "lightning:draw:"}）：两个消费者共用同一张限流表，
 * 不带前缀会导致"火焰先打了一条同文本的日志，闪电那条就再也不打了"。</p>
 *
 * <p><b>定位完就可以整类删掉</b>：把 {@link #ENABLED} 改 false（或直接删类 + 调用点）即可，不影响渲染逻辑。
 * 所有调用都在渲染线程（单线程），故 {@link #SEEN} 不需要同步。</p>
 */
public final class MirrorDiag {

    /**
     * 总开关：定位结束后置 false（或整类删除）即可。
     * <p>2026-10-03 起关闭：黑屏三条根因（FBO 还原到 0 号 / viewport 未跟着主目标 / 描边 fill 缺
     * {@code affects_outline} 门禁）已修完并留下注释档案，运行时不再需要逐帧诊断。
     * 需要再排查时把它改回 true 即可（限流逻辑与调用点都还在，零维护成本）。</p>
     */
    public static final boolean ENABLED = false;

    /** 已经放行过的 key（字符串，按内容比较）。 */
    private static final Set<String> SEEN = new HashSet<>();

    /**
     * 已放行过的对象（<b>按引用</b>比较）。给"每次 getBuffer 都会传进来"的 {@code RenderType} 这类热路径用：
     * 调用方不必先把 key 拼成字符串（{@code CompositeRenderType.toString()} 会把整串 CompositeState 拼出来，
     * 每帧每模型部件拼一次纯属浪费）。RenderType 本身是 memoize 出来的单例，持有引用不会额外占用内存。
     */
    private static final Set<Object> SEEN_IDENTITY = Collections.newSetFromMap(new IdentityHashMap<>());

    private MirrorDiag() {}

    /**
     * 限流闸门。
     *
     * @return {@code true} 表示这个 key 是第一次出现 —— 调用方据此打这一行日志
     */
    public static boolean first(String key) {
        if (!ENABLED) return false;
        return SEEN.add(key);
    }

    /**
     * 按<b>对象身份</b>限流的闸门（热路径专用，见 {@link #SEEN_IDENTITY}）。
     *
     * <p>注意：同一个对象上的多种日志会共用这一个名额，所以"同一对象的第二条日志"要另找判据
     * （例如 {@code MirrorChannel} 用自己的一对布尔标记）。</p>
     */
    public static boolean firstIdentity(Object key) {
        if (!ENABLED) return false;
        return SEEN_IDENTITY.add(key);
    }

    /** 实体标识（日志可读性用）：类型#实体 id。 */
    public static String entity(Entity entity) {
        if (entity == null) return "null";
        try {
            return entity.getType().toShortString() + "#" + entity.getId();
        } catch (Throwable t) {
            return entity.getClass().getSimpleName();
        }
    }

    /** 顶点格式的可读短名（{@code VertexFormat.toString()} 会把 7 个元素全打出来，太长）。 */
    public static String formatName(VertexFormat format) {
        if (format == null) return "null";
        if (format == DefaultVertexFormat.NEW_ENTITY) return "NEW_ENTITY";
        if (format == DefaultVertexFormat.BLOCK) return "BLOCK";
        if (format == DefaultVertexFormat.POSITION_COLOR) return "POSITION_COLOR";
        if (format == DefaultVertexFormat.POSITION_COLOR_NORMAL) return "POSITION_COLOR_NORMAL";
        if (format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) return "POSITION_COLOR_TEX_LIGHTMAP";
        if (format == DefaultVertexFormat.PARTICLE) return "PARTICLE";
        if (format == DefaultVertexFormat.POSITION) return "POSITION";
        return "其它(元素数=" + format.getElements().size() + ")";
    }
}
