package net.minecraft.client.yiz.client.render;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.yiz.modelmirror.MirrorDiag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>逐层贴图解析</b>（纯 CPU）：回答"这个 {@link RenderType} 这一层用的是哪张贴图"。
 *
 * <h2>为什么必须逐层</h2>
 * <p>一个实体一次渲染会走好几层 RenderType（本体 / 发光覆层 / 盔甲 / 手持物…），
 * <b>每层的 UV 属于各自那张贴图</b>。之前填充层对所有层都用"渲染器主贴图"，
 * 采别人那层的 UV 会落在基础贴图的不透明区 ⇒ 按贴图 alpha 的判据整片失效。
 * 本类只回答"该用哪张贴图"——<b>不碰任何 GL 状态</b>（既不 setSampler，也不 setShaderTexture，
 * 更不动混合；用它算出来的 ResourceLocation 只交给
 * {@code LockOutlineRenderType.fill(tex)} 里那句原版 {@code TextureStateShard}，与其它 RenderType 同构）。</p>
 *
 * <h2>两条解析路线（按顺序尝试，都失败就回退主贴图 = 现状）</h2>
 * <ol>
 *   <li><b>反射（首选，按"字段类型"找，不依赖字段名）</b>：
 *       {@code CompositeRenderType.state}（类型 {@code RenderType$CompositeState}）→
 *       {@code CompositeState.textureState}（类型 {@code RenderStateShard$EmptyTextureStateShard}）→
 *       该 shard 里那个装着 {@link ResourceLocation} 的 {@code Optional} 字段。
 *       <b>只按类型匹配</b>的原因：生产环境的成员名是 SRG（{@code m_xxxxx_}），按名字反射在正式包里必然失败，
 *       而<b>类名不会被混淆</b>，所以"类型名 + 字段类型"这条路在开发/生产两边都成立。
 *       注意 {@code setAccessible} 在模块化运行时可能被拒（{@code InaccessibleObjectException}）⇒ 有下面第二条兜底。</li>
 *   <li><b>{@code RenderType#toString()} 解析</b>：{@code CompositeRenderType.toString()} 就是
 *       {@code "RenderType[" + name + ":" + state + "]"}，而 {@code CompositeState.toString()}
 *       把 13 个 state shard 全拼出来，其中贴图 shard 长这样（本工程现场日志里逐字出现过）：
 *       {@code texture[Optional[minecraft:textures/entity/warden/warden.png](blur=false, mipmap=false)]}。
 *       纯字符串处理、零反射，是上一轮就验证过可行的路。</li>
 * </ol>
 *
 * <p>结果按 {@link RenderType} 身份缓存（{@link WeakHashMap}：实体层 RenderType 都是 memoize 出来的单例，
 * 而"每帧新建 RenderType"的 mod 也不会把这张表撑爆 —— 弱引用键会被回收；
 * 值里<b>不含</b>键的强引用，不会自锁）。</p>
 */
public final class LockOutlineLayerTexture {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    private static final String COMPOSITE_STATE = "net.minecraft.client.renderer.RenderType$CompositeState";
    private static final String EMPTY_TEXTURE_STATE = "net.minecraft.client.renderer.RenderStateShard$EmptyTextureStateShard";

    /** 精确形态：{@code texture[Optional[<ns>:<path>.png](blur=…)} */
    private static final Pattern PRECISE =
        Pattern.compile("texture\\[Optional\\[([^\\]]+?)\\]");
    /** 宽松形态：任何 {@code ns:….png}（万一某层不是 TextureStateShard 拼法时兜底）。 */
    private static final Pattern LOOSE =
        Pattern.compile("([a-z0-9_.\\-]+:[a-z0-9_./\\-]+\\.png)");

    private static final Map<RenderType, Optional<ResourceLocation>> CACHE =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** 本会话第一条成功/失败日志用（限流表由 {@link MirrorDiag} 管，条目数有限）。 */
    private static String lastStrategy = "未解析";

    private LockOutlineLayerTexture() {}

    /**
     * 解析该层自己的贴图。
     *
     * @return 该层贴图；{@code null} 表示解析不出（调用方必须回退到渲染器主贴图 = 现状）
     */
    @Nullable
    public static ResourceLocation resolve(@Nullable RenderType rt) {
        if (rt == null) return null;
        Optional<ResourceLocation> cached = CACHE.get(rt);
        if (cached != null) return cached.orElse(null);

        ResourceLocation resolved = null;
        String strategy = "无";
        try {
            resolved = byReflection(rt);
            if (resolved != null) strategy = "反射(字段类型)";
        } catch (Throwable ignored) {
            // 落到下一条路线
        }
        if (resolved == null) {
            try {
                resolved = byToString(rt);
                if (resolved != null) strategy = "toString 解析";
            } catch (Throwable ignored) {
                // 保持 null = 回退
            }
        }

        CACHE.put(rt, Optional.ofNullable(resolved));
        if (resolved != null) {
            lastStrategy = strategy;
            if (MirrorDiag.first("lock:layertex:ok")) {
                LOGGER.info("[LockOutline] 逐层贴图解析已生效：策略={} 示例 renderType={} → {}（纯 CPU，不碰任何 GL 状态）",
                        strategy, rt, resolved);
            }
        } else if (MirrorDiag.first("lock:layertex:miss")) {
            LOGGER.info("[LockOutline] 逐层贴图解析失败（该层回退到渲染器主贴图 = 现状）：renderType={}（只打第一条）", rt);
        }
        return resolved;
    }

    /** 当前生效的解析策略（诊断用）。 */
    public static String strategy() {
        return lastStrategy;
    }

    // ────────────────────────── 路线 1：反射（按类型找字段） ──────────────────────────

    @Nullable
    private static ResourceLocation byReflection(RenderType rt) throws Exception {
        Object state = fieldOfType(rt, COMPOSITE_STATE);
        if (state == null) return null;
        Object textureState = fieldOfType(state, EMPTY_TEXTURE_STATE);
        if (textureState == null) return null;
        // TextureStateShard#texture 是 private final Optional<ResourceLocation>（字段名会被 SRG 改名 ⇒ 只按类型找）
        for (Class<?> c = textureState.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != Optional.class) continue;
                f.setAccessible(true);
                Object value = f.get(textureState);
                if (value instanceof Optional<?> opt && opt.orElse(null) instanceof ResourceLocation rl) {
                    return rl;
                }
            }
        }
        return null;
    }

    /** 在 {@code owner}（含父类）里找一个"声明类型名 == typeName"的字段并读出来。 */
    @Nullable
    private static Object fieldOfType(Object owner, String typeName) throws Exception {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals(typeName)) continue;
                f.setAccessible(true);
                return f.get(owner);
            }
        }
        return null;
    }

    // ────────────────────────── 路线 2：toString 解析 ──────────────────────────

    @Nullable
    private static ResourceLocation byToString(RenderType rt) {
        String s;
        try {
            s = String.valueOf(rt);
        } catch (Throwable t) {
            return null;
        }
        if (s == null || s.indexOf("textures/") < 0) return null;

        Matcher precise = PRECISE.matcher(s);
        if (precise.find()) {
            ResourceLocation rl = parse(precise.group(1));
            if (rl != null) return rl;
        }
        Matcher loose = LOOSE.matcher(s);
        if (loose.find()) {
            return parse(loose.group(1));
        }
        return null;
    }

    @Nullable
    private static ResourceLocation parse(String text) {
        try {
            return new ResourceLocation(text.trim());
        } catch (Throwable t) {
            return null;
        }
    }
}
