package com.daisychain.block;

import com.daisychain.network.RedstoneLines;
import com.daisychain.registry.ModTags;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Step 161: redstone pipe. Carries an on / off signal along its line without losing strength: pull sides read a
 * signal, insert sides send it out. Connects to other redstone pipes, to redstone parts (anything that gives a signal,
 * plus the blocks in the daisychain:redstone_pipe_connects tag: lamps, pistons, doors...) and, as an output, to the
 * other Daisychain pipes, so their redstone settings can follow it. Not part of any item / fluid / energy network.
 */
public final class RedstonePipeBlock extends BasePipeBlock {

    public static final MapCodec<RedstonePipeBlock> CODEC = simpleCodec(RedstonePipeBlock::new);
    /** Step 162: the line is on. Set only by RedstoneLines, for the whole line at once; saved with the chunk. */
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");

    public RedstonePipeBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(POWERED);
    }

    // ---------------------------------------------------------------- step 162: the signal

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * Full power out of every output (insert) side while the line is on. direction points from the block asking toward
     * this pipe, so the pipe's own face is its opposite. Input (pull) sides never give a signal, so a line can't read
     * its own output there. Only the block state is read: neighbors ask this often.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWERED)
                && state.getValue(propertyFor(direction.getOpposite())) == SideState.CONNECTED ? 15 : 0;
    }

    /** Strong power, like a repeater: a solid block on an output side passes the signal on to its own neighbors. */
    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }

    @Override
    protected boolean signalOnlyChange(BlockState oldState, BlockState newState) {
        return oldState.setValue(POWERED, newState.getValue(POWERED)) == newState;
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level instanceof ServerLevel serverLevel && hasInput(state)) {
            RedstoneLines.get(serverLevel).markDirty(pos); // an input may read something else now
        }
    }

    private static boolean hasInput(BlockState state) {
        for (Direction direction : Direction.values()) {
            if (state.getValue(propertyFor(direction)) == SideState.EXTRACT) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && oldState != state
                && !(oldState.is(this) && signalOnlyChange(oldState, state))) {
            RedstoneLines.get(serverLevel).shapeChanged(pos); // placed or rewired: the lines here are built again
            // Step 163: a side that just connected to a lever, button, redstone block...: it becomes an input on the
            // next tick. Not when the player set it (wrench), nor on a new pipe pasted with its own settings.
            boolean pasted = !oldState.is(this) && state.getValue(HAS_DATA);
            if (!changingSideMode && !pasted) {
                boolean any = false;
                for (Direction direction : Direction.values()) {
                    if (state.getValue(propertyFor(direction)) == SideState.CONNECTED
                            && (!oldState.is(this) || oldState.getValue(propertyFor(direction)) == SideState.NONE)
                            && level.getBlockState(pos.relative(direction)).is(ModTags.REDSTONE_PIPE_INPUTS)) {
                        RedstoneLines.get(serverLevel).noteNewSource(pos, direction);
                        any = true;
                    }
                }
                if (any) {
                    serverLevel.scheduleTick(pos, this, 1);
                }
            }
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        super.tick(state, level, pos, random);
        int sources = RedstoneLines.get(level).takeNewSources(pos);
        for (Direction direction : Direction.values()) {
            BlockState now = level.getBlockState(pos);
            if ((sources & (1 << direction.get3DDataValue())) != 0 && now.is(this)
                    && now.getValue(propertyFor(direction)) == SideState.CONNECTED
                    && level.getBlockState(pos.relative(direction)).is(ModTags.REDSTONE_PIPE_INPUTS)) {
                setSideMode(level, pos, now, direction, null, MODE_PULL, false);
            }
        }
    }

    /** Step 163: a side wrenched to Output toward a block the pipe doesn't connect to on its own. */
    @Override
    protected boolean forcedOutput(BlockGetter level, BlockPos pos, Direction side) {
        return (outputMask(level, pos) & (1 << side.get3DDataValue())) != 0;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !state.is(newState.getBlock())) {
            RedstoneLines.get(serverLevel).shapeChanged(pos);
            if (state.getValue(POWERED)) {
                // The removal told the direct neighbors; blocks this pipe powered strongly tell theirs too.
                for (Direction direction : Direction.values()) {
                    if (state.getValue(propertyFor(direction)) == SideState.CONNECTED) {
                        RedstoneLines.notifyOutput(serverLevel, pos, direction, this);
                    }
                }
            }
        }
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
