package com.daisychain.block;

import com.daisychain.network.CapCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/** Item pipe: connects to other item pipes and to any block that exposes an item handler. */
public final class ItemPipeBlock extends FlowPipeBlock {


    public ItemPipeBlock(Properties properties) {
        super(properties);
    }


    @Override
    public PipeKind kind() {
        return PipeKind.ITEM;
    }

    @Override
    protected boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide) {
        return CapCache.has(level, targetPos, targetSide, ForgeCapabilities.ITEM_HANDLER);
    }
}
