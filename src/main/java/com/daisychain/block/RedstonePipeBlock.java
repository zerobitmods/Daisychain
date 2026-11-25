package com.daisychain.block;

import com.daisychain.registry.ModTags;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Step 161: redstone pipe. Carries an on / off signal along its line without losing strength: pull sides read a
 * signal, insert sides send it out. Connects to other redstone pipes, to redstone parts (anything that gives a signal,
 * plus the blocks in the daisychain:redstone_pipe_connects tag: lamps, pistons, doors...) and, as an output, to the
 * other Daisychain pipes, so their redstone settings can follow it. Not part of any item / fluid / energy network.
 */
public final class RedstonePipeBlock extends BasePipeBlock {

    public static final MapCodec<RedstonePipeBlock> CODEC = simpleCodec(RedstonePipeBlock::new);

    public RedstonePipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<RedstonePipeBlock> codec() {
        return CODEC;
    }

    @Override
    public PipeKind kind() {
        return PipeKind.REDSTONE;
    }

    @Override
    protected boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide) {
        BlockState target = level.getBlockState(targetPos);
        return target.isSignalSource() || target.is(ModTags.REDSTONE_PIPE_CONNECTS);
    }

    /** An output arm toward the other Daisychain pipes (item, fluid, energy, universal). Redstone pipes link instead. */
    @Override
    protected boolean connectsToOtherPipe(BlockState neighborState) {
        return neighborState.getBlock() instanceof BasePipeBlock pipe && !pipe.kind().isRedstone();
    }
}
