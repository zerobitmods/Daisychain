package com.daisychain.network;

import com.daisychain.block.FlowPipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;

/**
 * Step 111: watches one block entity next to our pipes for capability changes.
 * <p>
 * Some blocks change what they offer on a side without any block update. Mekanism machines do this when a side is
 * switched in their Side Config, and right after one is placed (the side config arrives after the block is set).
 * Pipes decide their arms on block updates, so without this an insert side could stay "nothing here" forever.
 * <p>
 * One watch per block entity, however many pipes touch it; NetworkManager keeps it alive (NeoForge only holds
 * listeners weakly). When it fires, every pipe of ours next to the block gets a re-check one tick later
 * (FlowPipeBlock.tick); nothing is read or changed while NeoForge is still telling its listeners.
 */
public final class NeighborWatch implements ICapabilityInvalidationListener {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final NetworkManager manager;
    private final ServerLevel level;
    final BlockPos pos;
    /** Set when its chunk unloads or nothing of ours is next to it any more; NeoForge then drops it. */
    volatile boolean removed;

    NeighborWatch(NetworkManager manager, ServerLevel level, BlockPos pos) {
        this.manager = manager;
        this.level = level;
        this.pos = pos;
    }

    @Override
    public boolean onInvalidate() {
        if (removed) {
            return false;
        }
        if (!level.getServer().isSameThread()) {
            return true; // a chunk event off the server thread: the queued chunk-load scan covers it
        }
        if (!Loaded.isLoaded(level, pos)) {
            return true; // the chunk is unloading (the queued unload drops this watch) or still loading
        }
        int pipes = scheduleRechecks(level, pos);
        if (pipes == 0 || !level.getBlockState(pos).hasBlockEntity()) {
            manager.dropWatch(this);
            return false;
        }
        return true;
    }

    /**
     * Asks every loaded pipe of ours next to pos to re-check its arms next tick. Returns how many there were.
     * Scheduled ticks are de-duplicated by the game, so asking twice in one tick costs nothing extra.
     */
    static int scheduleRechecks(ServerLevel level, BlockPos pos) {
        int count = 0;
        for (Direction direction : DIRECTIONS) {
            BlockPos pipePos = pos.relative(direction);
            // Review R4: never waits for a chunk that is still loading; that chunk's own load scan covers its pipes.
            BlockState state = Loaded.stateIfLoaded(level, pipePos);
            if (state != null && state.getBlock() instanceof FlowPipeBlock pipe) {
                level.scheduleTick(pipePos, pipe, 1);
                count++;
            }
        }
        return count;
    }
}
