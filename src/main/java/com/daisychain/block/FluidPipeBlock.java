package com.daisychain.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Fluid pipe: connects to other fluid pipes and to any block that exposes a fluid handler. */
public final class FluidPipeBlock extends FlowPipeBlock {

    public static final MapCodec<FluidPipeBlock> CODEC = simpleCodec(FluidPipeBlock::new);

    public FluidPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<FluidPipeBlock> codec() {
        return CODEC;
    }

    @Override
    public PipeKind kind() {
        return PipeKind.FLUID;
    }

    @Override
    protected boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide) {
        return level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, targetSide) != null;
    }
}
