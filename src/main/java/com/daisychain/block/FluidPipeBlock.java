package com.daisychain.block;

import com.daisychain.network.CapCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/** Fluid pipe: connects to other fluid pipes and to any block that exposes a fluid handler. */
public final class FluidPipeBlock extends FlowPipeBlock {


    public FluidPipeBlock(Properties properties) {
        super(properties);
    }


    @Override
    public PipeKind kind() {
        return PipeKind.FLUID;
    }

    @Override
    protected boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide) {
        return CapCache.has(level, targetPos, targetSide, ForgeCapabilities.FLUID_HANDLER);
    }
}
