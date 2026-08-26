package net.minecraft.client.yiz.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 属性编辑台的 BlockEntity，持久化存储放置槽物品（1.20.1 移植版）。
 *
 * <p>放置槽存 index 0（1 格），物品在方块破坏/替换时掉落。
 * saveAdditional/loadAdditional 保证退出重进世界后物品还在。</p>
 */
public class AttributeEditorBlockEntity extends BlockEntity {

    private final NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);

    public AttributeEditorBlockEntity(BlockPos pos, BlockState blockState) {
        super(AttributeEditorRegistries.ATTRIBUTE_EDITOR_BLOCK_ENTITY.get(), pos, blockState);
    }

    // ── 容器访问 ──────────────────────────────────────────────

    public NonNullList<ItemStack> getItems() {
        return items;
    }

    public ItemStack getPlacedItem() {
        return items.get(0);
    }

    public void setPlacedItem(ItemStack stack) {
        items.set(0, stack);
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * 返回一个 {@link Container} 接口，由 Menu 使用。
     */
    public Container getOrCreateContainer() {
        return new Container() {
            @Override public int getContainerSize() { return 1; }
            @Override public boolean isEmpty() { return items.get(0).isEmpty(); }
            @Override public ItemStack getItem(int slot) { return slot == 0 ? items.get(0) : ItemStack.EMPTY; }
            @Override public ItemStack removeItem(int slot, int amount) {
                if (slot != 0 || items.get(0).isEmpty()) return ItemStack.EMPTY;
                ItemStack result = items.get(0).split(amount);
                if (items.get(0).isEmpty()) items.set(0, ItemStack.EMPTY);
                setChanged();
                return result;
            }
            @Override public ItemStack removeItemNoUpdate(int slot) {
                if (slot != 0) return ItemStack.EMPTY;
                ItemStack result = items.get(0);
                items.set(0, ItemStack.EMPTY);
                setChanged();
                return result;
            }
            @Override public void setItem(int slot, ItemStack stack) {
                if (slot == 0) { items.set(0, stack); setChanged(); }
            }
            @Override public void setChanged() { AttributeEditorBlockEntity.this.setChanged(); }
            @Override public boolean stillValid(Player player) {
                return level != null && level.getBlockEntity(worldPosition) == AttributeEditorBlockEntity.this
                    && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
            }
            @Override public void clearContent() { items.set(0, ItemStack.EMPTY); setChanged(); }
        };
    }

    // ── 持久化（1.20.1 无 HolderLookup.Provider 参数）────────

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items.clear();
        ContainerHelper.loadAllItems(tag, items);
    }

    // ── 客户端同步 ────────────────────────────────────────────

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        ContainerHelper.saveAllItems(tag, items);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
        items.clear();
        ContainerHelper.loadAllItems(tag, items);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        handleUpdateTag(pkt.getTag());
    }

    // ── 掉落 ──────────────────────────────────────────────────

    /** 方块被破坏/替换时，由外部调用取出放置槽物品用于掉落。 */
    public void dropContents() {
        if (level != null && !level.isClientSide) {
            ItemStack stack = getPlacedItem();
            if (!stack.isEmpty()) {
                net.minecraft.world.level.block.Block.popResource(level, worldPosition, stack.copy());
                setPlacedItem(ItemStack.EMPTY);
            }
        }
    }
}
