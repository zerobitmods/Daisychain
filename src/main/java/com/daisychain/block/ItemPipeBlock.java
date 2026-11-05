package com.daisychain.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Item pipe: connects to other item pipes and to any block that exposes an item handler. */
public final class ItemPipeBlock extends FlowPipeBlock {

    public static final MapCodec<ItemPipeBlock> CODEC = simpleCodec(ItemPipeBlock::new);

    public ItemPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<ItemPipeBlock> codec() {
        return CODEC;
    }

    @Override
    public PipeKind kind() {
        return PipeKind.ITEM;
    }

    @Override
    protected boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, targetSide) != null;
    }
}
