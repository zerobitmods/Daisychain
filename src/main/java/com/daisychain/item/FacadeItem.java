package com.daisychain.item;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModItems;
import com.daisychain.registry.ModTags;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Step 125 (facades, zerobit 2026-11-14): a cover for a pipe. Remembers its block in the daisychain:facade data
 * component. Right-click a pipe to cover it (the pipe block handles the click); sneak + right-click the pipe with an
 * empty hand takes it off. Looks only: the pipe keeps connecting, pulling and inserting on every side.
 */
public final class FacadeItem extends Item {

    public FacadeItem(Properties properties) {
        super(properties);
    }

    /** The block this facade covers a pipe with, or null for a facade without one. */
    @Nullable
    public static BlockState coverOf(ItemStack stack) {
        return stack.get(ModDataComponents.FACADE.get());
    }

    /** A stack of facades of this block. */
    public static ItemStack of(BlockState cover, int count) {
        ItemStack stack = new ItemStack(ModItems.FACADE.get(), count);
        stack.set(ModDataComponents.FACADE.get(), cover);
        return stack;
    }

    /** The cover a block item makes (its block's default state), or null if that block can't be a facade. */
    @Nullable
    public static BlockState coverFor(ItemStack blockStack) {
        if (blockStack.getItem() instanceof BlockItem blockItem) {
            BlockState state = blockItem.getBlock().defaultBlockState();
            return canCover(state) ? state : null;
        }
        return null;
    }

    /**
     * True if this block can be a cover: a full cube drawn by a normal block model (glass included). Never a pipe,
     * a block with a block entity (its look may depend on data the cover doesn't have), or anything in the block tag
     * daisychain:not_facades (for pack makers). Checked when crafting and again when a facade goes on a pipe, because
     * a creative player can edit what a facade holds.
     */
    public static boolean canCover(@Nullable BlockState state) {
        if (state == null || state.isAir() || state.getBlock() instanceof FlowPipeBlock || state.hasBlockEntity()
                || state.getRenderShape() != RenderShape.MODEL || state.is(ModTags.NOT_FACADES)) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        } catch (RuntimeException e) {
            return false; // a modded block that can't work out its shape without a real world: not a cover
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        BlockState cover = coverOf(stack);
        return cover == null ? super.getName(stack)
                : Component.translatable("item.daisychain.facade.named", cover.getBlock().getName());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (coverOf(stack) == null) {
            tooltip.add(Component.translatable("tooltip.daisychain.facade.empty").withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.daisychain.facade.looks").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.facade.apply").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.facade.mass").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.daisychain.facade.remove").withStyle(ChatFormatting.DARK_GRAY));
    }
}
