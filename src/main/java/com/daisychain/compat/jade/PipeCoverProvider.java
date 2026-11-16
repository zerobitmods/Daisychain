package com.daisychain.compat.jade;

import com.daisychain.Daisychain;
import com.daisychain.block.CoverShape;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.item.FacadeItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

import javax.annotation.Nullable;

/**
 * Step 136 (facades step 5): a covered pipe keeps its own name and icon in Jade and gets a grey "Cover: Oak Planks"
 * line under the side info. Client only; the cover is already synced to the client with the block entity.
 * The icon needs its own override because pick block on a covered pipe gives the facade (step 136), and Jade draws
 * the picked item as the icon by default.
 */
public enum PipeCoverProvider implements IBlockComponentProvider {
    INSTANCE;

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "pipe_cover");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** After the side lines from PipeInfoProvider (priority 0), before Jade's mod name. */
    @Override
    public int getDefaultPriority() {
        return 10;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        BlockState cover = coverOf(accessor);
        if (cover == null) {
            return;
        }
        Component name = FacadeItem.isTransparent(cover) ? Component.translatable("jade.daisychain.cover.transparent")
                : cover.getBlock().getName();
        tooltip.add(Component.translatable("jade.daisychain.cover", name).withStyle(ChatFormatting.GRAY));
    }

    @Override
    @Nullable
    public IElement getIcon(BlockAccessor accessor, IPluginConfig config, @Nullable IElement currentIcon) {
        if (coverOf(accessor) == null) {
            return currentIcon;
        }
        return IElementHelper.get().item(new ItemStack(accessor.getBlock()));
    }

    @Nullable
    private static BlockState coverOf(BlockAccessor accessor) {
        BlockState state = accessor.getBlockState();
        if (!(state.getBlock() instanceof FlowPipeBlock) || state.getValue(FlowPipeBlock.COVER) == CoverShape.NONE) {
            return null;
        }
        PipeDataBlockEntity data = FlowPipeBlock.getData(accessor.getLevel(), accessor.getPosition(), state);
        return data == null ? null : data.cover();
    }
}
