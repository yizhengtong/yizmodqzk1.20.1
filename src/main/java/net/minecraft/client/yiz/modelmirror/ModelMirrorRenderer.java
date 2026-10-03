package net.minecraft.client.yiz.modelmirror;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 模型几何镜像的<b>通用刷帧驱动</b>：把 {@link ModelMirrorBufferSource} 本帧双写下来的实体模型几何，
 * 逐个实体 × 逐个消费者画出来（目前只有贴模型火焰这一个消费者；
 * 贴模型闪电的两次尝试都因"一开即整屏发黑"整层移除了，见 {@link ModelMirrorLayers}）。
 *
 * <p><b>为什么在 AFTER_ENTITIES 画</b>：本 pass 的顶点是实体渲染时按当时的 PoseStack
 * 烤进缓冲的（视图空间），必须趁 <b>ModelViewMat 还是实体渲染当时那个矩阵</b>时刷帧；
 * 一过 {@code LevelRenderer} 的关卡姿态处理，矩阵语义就变了。选 {@code AFTER_ENTITIES} 还有两个正好需要的副作用：</p>
 * <ol>
 *   <li>此时实体本体的批次已经 flush（{@code LevelRenderer.renderLevel} 在派发该阶段前
 *       {@code endLastBatch()} + 逐类型 {@code endBatch}），所以特效画在<b>本体之后</b> ——
 *       否则本体后画会把"贴着身体的那层效果"整片盖掉（本 pass 不写深度，盖不住）；</li>
 *   <li>此时深度缓冲里已经有身体自己的深度，（火焰的）逐样本深度截断与（闪电的）硬件深度测试
 *       于是顺带把"身体背面的效果"正确挡住（不然会有穿身而过的 X 光效果）。</li>
 * </ol>
 *
 * <p><b>逐实体逐层刷帧</b>：uniform（噪声锚点 / 蔓延进度 / 渐显系数）与 RenderType 逐层不同，
 * 无法合并成一次 draw call；每个实体每个激活层一次 draw，数量极少（只有真正带效果的少数实体）。</p>
 *
 * <p><b>渲染目标归属（不变量，务必守住）</b>：本 pass 的每一层 RenderType 都不自己切渲染目标
 * ——{@code MAIN_TARGET} 是空实现（{@code RenderStateShard.java:172-174}），
 * {@code ITEM_ENTITY_TARGET} 只在"极佳"画质下才真的切（{@code :224-234}）。
 * 也就是说"画到哪"完全由<b>进来时绑的是谁</b>决定，而本阶段的正确目标恒为主渲染目标。
 * 所以本 pass 在画之前<b>显式绑一次主目标并声明所有权</b>（{@code bindWrite(true)} +
 * {@link RenderStateSnapshot#ownFramebuffer()}），与描边管线（它的 fill 目标会临时绑自己的 FBO）
 * 彻底互斥：谁都不能靠"别人收尾"来保证自己不画错地方 —— 画进描边 FBO 就等于主目标收不到画面，
 * 表现是整屏发黑/冻结且不会自愈。</p>
 */
public final class ModelMirrorRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    private ModelMirrorRenderer() {}

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        List<ModelMirrorBufferSource.Batch> batches = ModelMirrorBufferSource.batchesSnapshot();
        if (batches.isEmpty()) {
            // ── 临时诊断：一条批次都没有 = 本帧没有任何实体进入镜像双写 ──
            // 两种典型情况：① 有状态但目标实体根本没走到实体渲染（例：第一人称下"自己"着火/带电时，
            // 原版 LevelRenderer 的实体循环会跳过摄像机实体 → 贴模型效果没有几何可画，
            // 此时体积特效兜底属预期行为，切第三人称即可验证）；
            // ② 全部实体都没带效果（正常）。
            if (MirrorDiag.first("mm:noBatch")) {
                LOGGER.info("[ModelMirror] 本帧没有任何批次（没有实体进入镜像双写）—— "
                        + "若此时确实有实体带效果，检查视角/是否为目标实体（第一人称不渲染摄像机实体）");
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // ── 临时诊断（全局一次）：进 pass 前 0 号纹理单元到底指着谁 ──
        // 用途：黑屏类问题的判定。1.20.1 的 RenderSystem.setShaderTexture 只写内部数组，
        // 之后每个"程序里有 Sampler0、自己又没设贴图"的 draw 都会照这个值绑 —— 若它指向某个
        // 内部 FBO（典型：描边后处理的描边 FBO 颜色纹理，每帧被清成 (0,0,0,0) = 纯黑），
        // 后续渲染就会整片发黑，且不会自愈。这条日志让"到底是不是纹理单元被留在别人身上"一眼可判。
        if (MirrorDiag.first("mm:unit0")) {
            LOGGER.info("[ModelMirror] 0 号纹理单元当前值={}（主目标：颜色={} 深度={}）—— "
                            + "若三者都不相等、也不是任何实体贴图，说明上一个 pass 把 Sampler0 留在自己身上了",
                    RenderSystem.getShaderTexture(0),
                    mc.getMainRenderTarget().getColorTextureId(),
                    mc.getMainRenderTarget().getDepthTextureId());
        }

        // ── 临时诊断（黑屏现场取证）：本 pass 与描边 pass 同在 AFTER_ENTITIES ──
        // 必须在"自己绑主目标之前"读：这一行要回答的正是"实体渲染阶段/描边 fill 收尾之后，
        // 留下来的到底是不是主目标"；异常时间限流打一条（见 RenderTargetProbe）。
        RenderTargetProbe.once("modelmirror");

        // ── 先走一遍"批次生命周期"（与 GL 无关）：丢掉没有活干的批次，并判断本帧有没有层要画 ──
        // 两件事都在这里做，是因为它们必须每帧都做（没活干的批次要被回收，否则 BATCHES 会越攒越多），
        // 而"围弧（编号 4）的锚点接收器"只是一个"只看不写"的顶点接收器 ——
        // 它不需要任何 GL 调用，所以"本帧只有锚点任务、没有任何镜像层"时直接返回：
        // 连全局状态快照与渲染目标绑定都省掉（那条路只在真的有层要画时才值得走）。
        boolean anyLayer = false;
        for (ModelMirrorBufferSource.Batch batch : batches) {
            Entity entity = batch.entity();
            // 本帧既没有激活的镜像层、也没有锚点采集任务 → 丢掉这批顶点。
            // 注意"只要锚点"的批次要留着：它本帧没有通道要刷，但锚点表（归 ShockArcs 持有）
            // 已经在实体渲染时采过了 —— 批次留着只是省掉"每帧新建 Batch/接收器"的那点分配。
            if ((!batch.hasActiveChannel() && !batch.wantsAnchors()) || entity.isRemoved()) {
                // 状态没了（指令关掉 / 实体死亡/卸载）→ 丢掉这批顶点，别带着过期 uniform 画出去
                ModelMirrorBufferSource.forget(batch);
                continue;
            }
            if (batch.hasActiveChannel()) anyLayer = true;
        }
        if (!anyLayer) return;

        // ── 全局状态快照 → try/finally 全量还原 ──
        // 本 pass 会改：① 当前 RenderTarget（火焰层要"输出落在主目标"，才与它采样的深度纹理同源）；
        // ② 0 号纹理单元的 shaderTextures 记录（借它当场景深度纹理）；
        // 而各层 draw() 触发的 RenderType.setup/clearRenderState 还会顺带改「当前着色器、着色器颜色、
        // 混合开关+混合函数、深度测试、深度掩码、背面剔除」——其中 NO_DEPTH_TEST 的 clearState 是
        // disableDepthTest()、COLOR_WRITE 的是 depthMask(true)，都是"看着对称、其实不对称"的项。
        // 这些全部是全局状态：漏出去 = 后面所有渲染一起带着错状态跑（整屏发黑/花屏且不会自愈）。
        // 所以这里不逐项手写还原，而是快照一份、finally 里整份还回去（逐项理由见 RenderStateSnapshot）。
        RenderStateSnapshot snapshot = RenderStateSnapshot.capture();
        try {
            // ── 显式声明"本 pass 要求主渲染目标"，不再依赖上一个 pass 的收尾 ──
            // 各层 RenderType 的 OutputStateShard 里：MAIN_TARGET 是空实现
            // （RenderStateShard.java:172-174），ITEM_ENTITY_TARGET 只在"极佳 Fabulous"下才真的切目标
            // （:224-234，非极佳时同样什么都不做；本机 options.txt graphicsMode=1=Fancy）。
            // 本 pass 现在<b>只剩一层</b>（贴模型火焰），用 MAIN_TARGET 空实现 ——
            // 原先唯一用 ITEM_ENTITY_TARGET 的 surface 类型只服务实体 pass 里的编号 5 mixin，
            // 而 5 号已在第三轮清理中删除（连带该 RenderType 与着色器资源），所以本 pass 里
            // 与"闪电类 RenderType"有关的东西<b>一个都不剩</b>（见 ModelMirrorLayers 类注释）。
            // ⚑ 本轮补充取证：输出目标<b>不是</b>"镜像层黑屏"的共因 —— 被移除的 3 号 AIR_ARC 层
            //    用的就是 MAIN_TARGET（空实现），照样一开即黑屏。所以"这些顶点画到哪"虽然仍必须守住
            //    （见下），但恢复闪电类层时要查的是"混合/全局状态有没有被着色器程序自己改脏"
            //    （首要嫌疑：JSON 缺 "blend" 节点 ⇒ ShaderInstance#apply → BlendMode#apply →
            //    RenderSystem.disableBlend()，全工程仅那两个闪电层如此；取证见 ModelMirrorLayers）。
            // 也就是说"这些顶点画到哪"完全取决于<b>进来时绑的是谁</b>：
            //   · 描边管线的 fill 目标会在它的 setupRenderState 里绑自己的 FBO
            //     （LockOutlineRenderType.java:32-35 的 FILL_TARGET），虽然它的 clear 又把主目标切了回来，
            //     但"靠别人收尾"正是黑屏那一类问题的温床 —— 一旦某次 setup 之后没走到 clear
            //     （异常 / 第三方 RenderType 收尾不对称），镜像层就会画进描边 FBO，
            //     而画面是从主目标 blit 出去的 → 整屏发黑且不会自愈。
            // 所以这里在画任何东西之前显式绑一次主目标，并用 snapshot.ownFramebuffer() 声明所有权：
            // 退出时 RenderStateSnapshot 交还主目标（永不回到 0 号默认帧缓冲）。
            // bindWrite(true) 而不是 false：连 viewport 一起校准成主目标的尺寸
            // （RenderTarget.clear()/_resize 会把全局 viewport 改成别的 FBO 的尺寸，那是全局状态）。
            mc.getMainRenderTarget().bindWrite(true);
            snapshot.ownFramebuffer();

            // 深度纹理绑定是"按需"的：只有声明 needsSceneDepth 的层才会付这次绑定
            // （贴模型体表电流用硬件深度测，不需要；贴模型火焰要，见 FlameSurfaceLayer）。
            boolean depthBound = false;
            for (ModelMirrorBufferSource.Batch batch : batches) {
                Entity entity = batch.entity();
                // 生命周期已经在上面统一走过（要丢的批次已经丢了），这里只处理"本帧有层要画"的批次
                if (!batch.hasActiveChannel() || entity.isRemoved()) continue;

                for (int i = 0; i < batch.channelCount(); i++) {
                    MirrorChannel channel = batch.channelAt(i);
                    if (channel == null || !channel.active() || !channel.dirty()) {
                        // ── 临时诊断（定位"效果完全看不见"）：本层激活、却一个顶点都没抄到 ──
                        if (channel != null && channel.active()
                                && MirrorDiag.first("mm:empty:" + channel.layer().id() + ":" + MirrorDiag.entity(entity))) {
                            LOGGER.info("[ModelMirror] 跳过: layer={} entity={} 顶点=0（几何未被抄到：模型 pass 没经过双写，"
                                            + "或全被格式/影子/装备门禁挡下）",
                                    channel.layer().id(), MirrorDiag.entity(entity));
                        }
                        continue;
                    }
                    MirrorLayer layer = channel.layer();
                    if (layer.needsSceneDepth() && !depthBound) {
                        // 双保险绑定主渲染目标（pass 入口已经绑过一次；这里是"逐层兜底"的写法保留下来）：
                        // 本 pass 的深度截断用的是主目标深度纹理，输出也必须落在主目标上，两者必须同源。
                        // bindWrite(true)：连 viewport 一起校准成主目标尺寸（false 会把可能已经脏掉的
                        // viewport 一路带下去）。
                        mc.getMainRenderTarget().bindWrite(true);
                        // Sampler0 = 主渲染目标的深度纹理（片元着色器逐样本比对场景深度做遮挡/截断）。
                        // 它的原值由 snapshot 保存并在 finally 里整份还回去 —— 不在这里手写 prevDepthTex：
                        // 手写只能覆盖"正常返回"这一条路径，且容易漏掉"进来时 0 号单元已经是脏值"的情况。
                        RenderSystem.setShaderTexture(0, mc.getMainRenderTarget().getDepthTextureId());
                        depthBound = true;
                    }
                    layer.draw(channel);
                }
            }
        } finally {
            // 无条件整份还原：纹理单元 0..11、着色器、着色器颜色、FBO 绑定、混合/剔除/深度测试/深度掩码。
            snapshot.restore("modelmirror");
        }
    }
}
