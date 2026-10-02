package net.minecraft.client.yiz.modelmirror;

/**
 * 哪些"外挂网格"不参与模型几何镜像 —— 手持物 / 掉落物 / 展示框物品 / 盔甲。
 *
 * <p><b>为什么需要它</b>：镜像机制抄的是"实体渲染期间所有 NEW_ENTITY 顶点"，
 * 而手持物品用的 {@code entity_translucent_cull}（绑 {@code atlas/blocks.png}）与实体本体用的
 * {@code entity_cutout_no_cull} 是<b>同一批 RenderType 名字</b>，盔甲的 {@code armor_cutout_no_cull}
 * 也是同一族的体素网格 —— <b>靠 RenderType 名字/格式区分不了</b>"这是身体还是挂在身上的东西"。
 * 于是改用"渲染期标志"：进入物品/盔甲的渲染入口时置位，退出时清除；
 * 镜像的 {@code getBuffer} 遇到置位就只转发、不抄顶点。</p>
 *
 * <p><b>为什么不按 RenderType 的贴图判据</b>（"是不是方块图集就是物品"）：</p>
 * <ol>
 *   <li>{@code RenderType} 不暴露贴图（{@code CompositeRenderType} 的 {@code CompositeState} 无访问器，
 *       {@code TextureStateShard.texture} 是 protected），要拿到得额外加 Accessor mixin 并反射式读私有状态 ——
 *       比"渲染期标志"更脆；</li>
 *   <li>贴图判据<b>漏盔甲</b>（盔甲是 {@code armor_cutout_no_cull} + 盔甲贴图，不在方块图集里），
 *       最后还是得为盔甲再加一套标志，等于两套判据并存；</li>
 *   <li>方块图集也被别的实体部位用到（例如部分 MOD 模型直接把方块图集当实体贴图），
 *       贴图判据会误伤这些本体几何。</li>
 * </ol>
 *
 * <p><b>为什么用 ThreadLocal 深度而不是布尔</b>：① 物品渲染里会嵌套物品渲染
 * （自定义渲染器 / 副手 / 展示框），计数器保证"最外层退出时"才清除；
 * ② 渲染线程是唯一会走到这里的线程，但万一某个 MOD 在别的线程调 {@code ItemRenderer}，
 * ThreadLocal 也不会把主线程的判定污染成"永远在渲染物品"（那会让特效整帧消失，很难查）。</p>
 *
 * <p><b>标志可能泄漏的后果是安全方向</b>：万一某个渲染入口抛异常导致计数没减回去，
 * 结果是"物品/盔甲不再被抄"（本来是期望行为），而不是画错几何。</p>
 */
public final class MirrorExclusion {

    private static final int ITEM = 0;
    private static final int ARMOR = 1;

    /** 每线程一份深度：[物品, 盔甲]。 */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[2]);

    private MirrorExclusion() {}

    /** 进入物品渲染（{@code ItemRenderer.render}）。 */
    public static void enterItem() {
        DEPTH.get()[ITEM]++;
    }

    /** 退出物品渲染（正常/异常返回都走这里，由 mixin 的 HEAD/RETURN 成对调用）。 */
    public static void exitItem() {
        int[] d = DEPTH.get();
        if (d[ITEM] > 0) d[ITEM]--;
    }

    /** 进入盔甲层渲染（{@code HumanoidArmorLayer.render}）。 */
    public static void enterArmor() {
        DEPTH.get()[ARMOR]++;
    }

    /** 退出盔甲层渲染。 */
    public static void exitArmor() {
        int[] d = DEPTH.get();
        if (d[ARMOR] > 0) d[ARMOR]--;
    }

    /** 当前是否处于"外挂网格"渲染期：是 → 镜像只转发、不抄顶点。 */
    public static boolean isExcluded() {
        int[] d = DEPTH.get();
        return d[ITEM] > 0 || d[ARMOR] > 0;
    }

    /** 是哪个标志挡住了（诊断日志用；与 {@link #isExcluded()} 分开只为让日志能说清原因）。 */
    public static String reason() {
        int[] d = DEPTH.get();
        if (d[ITEM] > 0) return "物品(深度=" + d[ITEM] + ")";
        if (d[ARMOR] > 0) return "盔甲(深度=" + d[ARMOR] + ")";
        return "无";
    }
}
