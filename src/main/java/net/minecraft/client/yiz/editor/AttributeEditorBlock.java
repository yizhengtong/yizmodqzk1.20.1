package net.minecraft.client.yiz.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 属性编辑台方块（1.20.1 移植版）。
 *
 * <p>库模组第一个实体方块容器，右键打开 GUI 编辑物品属性。
 * 放置槽物品持久化在 BlockEntity 中，方块破坏时掉落。</p>
 */
public class AttributeEditorBlock extends BaseEntityBlock {

    private static final Component TITLE = Component.translatable("block.yizmodqzk.attribute_editor");

    public AttributeEditorBlock(Properties properties) {
        super(properties);
    }

    // ── 方块属性 ──────────────────────────────────────────────

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** 透明方块：相邻方块的面不被剔除。 */
    @Override
    public boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
        return adjacentState.is(this) || super.skipRendering(state, adjacentState, direction);
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    // ── 右键交互（1.20.1 use API）────────────────────────────

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer) {
            ServerPlayer serverPlayer = (ServerPlayer) player;
            // 1.20.1 只有单参 openMenu（容器 stillValid 用 distanceToSqr 检查）
            serverPlayer.openMenu(getMenuProvider(state, level, pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AttributeEditorBlockEntity editorBe)) return null;
        return new SimpleMenuProvider(
            (containerId, playerInv, p) ->
                new AttributeEditorMenu(containerId, playerInv, editorBe.getOrCreateContainer()),
            TITLE);
    }

    // ── BlockEntity ───────────────────────────────────────────

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AttributeEditorBlockEntity(pos, state);
    }

    // ── 方块破坏掉落 ──────────────────────────────────────────

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof AttributeEditorBlockEntity editorBe) {
                editorBe.dropContents();
            }
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }
}
