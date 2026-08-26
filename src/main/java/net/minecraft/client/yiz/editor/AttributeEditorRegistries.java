package net.minecraft.client.yiz.editor;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 属性编辑台方块的注册中心（1.20.1 移植版）。
 *
 * <p>库模组的第一个实体方块，提供可视化 GUI 编辑物品的自定义属性。
 * 注册命名空间 {@code yizmodqzk}，注册名 {@code attribute_editor}。
 * 1.20.1 差异：DeferredHolder→RegistryObject、BlockEntityType 无 dataType 参数、
 * MenuType 无 FeatureFlags 参数；SkillAssemblyBlock（技能装配台）未移植（用户只需属性编辑台）。</p>
 */
public final class AttributeEditorRegistries {

    private AttributeEditorRegistries() {}

    /** 方块 DeferredRegister。 */
    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, "yizmodqzk");

    /** 物品 DeferredRegister（用于注册 BlockItem）。 */
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, "yizmodqzk");

    /** 创造标签页 DeferredRegister（库自己的专属标签页）。 */
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
        DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, "yizmodqzk");

    /** BlockEntity 类型 DeferredRegister。 */
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "yizmodqzk");

    /** Menu 类型 DeferredRegister。 */
    public static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, "yizmodqzk");

    // ── 方块 ──────────────────────────────────────────────────

    /** 属性编辑台方块：石质、金属声、不可燃，透明需 noOcclusion。 */
    public static final RegistryObject<AttributeEditorBlock> ATTRIBUTE_EDITOR_BLOCK =
        BLOCKS.register("attribute_editor",
            () -> new AttributeEditorBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.5f)
                .sound(SoundType.METAL)
                .noOcclusion()
                .requiresCorrectToolForDrops()));

    /** 对应的 BlockItem（让方块能进入创造栏/可 give）。 */
    public static final RegistryObject<BlockItem> ATTRIBUTE_EDITOR_ITEM =
        ITEMS.register("attribute_editor",
            () -> new BlockItem(ATTRIBUTE_EDITOR_BLOCK.get(), new Item.Properties()));

    // ── 创造标签页 ────────────────────────────────────────────

    /** 工作方块标签页（标题走语言文件 itemGroup.yizmodqzk.workbench，图标=属性编辑台）。 */
    public static final RegistryObject<CreativeModeTab> WORKBENCH_TAB =
        CREATIVE_TABS.register("workbench",
            () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.yizmodqzk.workbench"))
                .icon(() -> new ItemStack(ATTRIBUTE_EDITOR_ITEM.get()))
                .displayItems((params, output) -> {
                    output.accept(ATTRIBUTE_EDITOR_ITEM.get());
                })
                .build());

    // ── BlockEntity 类型 ──────────────────────────────────────

    /** 属性编辑台 BlockEntity 类型（1.20.1 构造无 dataType 参数）。 */
    public static final RegistryObject<BlockEntityType<AttributeEditorBlockEntity>> ATTRIBUTE_EDITOR_BLOCK_ENTITY =
        BLOCK_ENTITIES.register("attribute_editor",
            () -> new BlockEntityType<>(
                AttributeEditorBlockEntity::new,
                java.util.Set.of(ATTRIBUTE_EDITOR_BLOCK.get()),
                null)
        );

    // ── Menu 类型 ─────────────────────────────────────────────

    /** 属性编辑台 Menu 类型（客户端构造走 createClientMenu）。 */
    public static final RegistryObject<net.minecraft.world.inventory.MenuType<AttributeEditorMenu>> ATTRIBUTE_EDITOR_MENU =
        MENUS.register("attribute_editor",
            () -> new net.minecraft.world.inventory.MenuType<>(AttributeEditorMenu::createClientMenu,
                net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));

    // ── 注册入口 ──────────────────────────────────────────────

    /** 在 modEventBus 上注册全部 DeferredRegister。由 tizMod 构造器调用。 */
    public static void register(net.minecraftforge.eventbus.api.IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
    }
}
