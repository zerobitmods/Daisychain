package com.daisychain.block;

import com.daisychain.network.CapCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/**
 * Universal pipe (step 33): carries items, fluids and energy at once. Connects to other universal pipes and to any
 * block that exposes an item handler, a fluid handler or an energy storage.
 */
public final class UniversalPipeBlock extends FlowPipeBlock {


    public UniversalPipeBlock(Properties properties) {
        super(properties);
    }


    @Override
    public PipeKind kind() {
        return PipeKind.UNIVERSAL;
    }

    @Override
    protected boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide) {
        return CapCache.has(level, targetPos, targetSide, ForgeCapabilities.ITEM_HANDLER)
                || CapCache.has(level, targetPos, targetSide, ForgeCapabilities.FLUID_HANDLER)
                || CapCache.has(level, targetPos, targetSide, ForgeCapabilities.ENERGY);
    }
}
