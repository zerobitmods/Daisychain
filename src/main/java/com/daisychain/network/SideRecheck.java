package com.daisychain.network;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.SideState;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * F40: pipe sides that face a block entity offering nothing a pipe can use yet, re-checked every couple of seconds.
 * <p>
 * Some blocks start offering an inventory later without telling anyone: Functional Storage's Controller Access Point
 * only has one once it is linked to a controller, and on 1.20.1 linking sends no block update or neighbor update.
 * Forge has no way to listen for a capability that doesn't exist yet, so these sides are polled, slowly and cheaply:
 * only sides facing a block entity, only while they show no arm, one capability lookup per side per sweep. A side
 * leaves the list as soon as it connects, gets another state, or the block entity is gone.
 * <p>
 * Server thread only. Positions are packed BlockPos longs; the mask holds the side bits (Direction.get3DDataValue).
 */
public final class SideRecheck {
    /** Every waiting side is looked at once per this many ticks. */
    public static final int SWEEP_TICKS = 40;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final ServerLevel level;
    private final Long2ByteOpenHashMap masks = new Long2ByteOpenHashMap();
    private final LongArrayList order = new LongArrayList();
    private int cursor;
    private final BlockPos.MutableBlockPos pipePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

    SideRecheck(ServerLevel level) {
        this.level = level;
    }

    /** The pipe at pipe shows no arm toward a block entity in direction side; look again later. */
    public void add(BlockPos pipe, Direction side) {
        long key = pipe.asLong();
        byte old = masks.get(key);
        if (old == 0) {
            order.add(key);
        }
        masks.put(key, (byte) (old | (1 << side.get3DDataValue())));
    }

    public int size() {
        return masks.size();
    }

    /** Checks about 1/SWEEP_TICKS of the waiting sides. No allocation. */
    void tick() {
        int size = order.size();
        if (size == 0) {
            return;
        }
        int budget = (size + SWEEP_TICKS - 1) / SWEEP_TICKS;
        while (budget-- > 0 && !order.isEmpty()) {
            if (cursor >= order.size()) {
                cursor = 0;
            }
            long key = order.getLong(cursor);
            byte mask = check(key, masks.get(key));
            if (mask == 0) {
                masks.remove(key);
                // swap-remove; the moved entry is looked at next
                int last = order.size() - 1;
                order.set(cursor, order.getLong(last));
                order.removeLong(last);
            } else {
                masks.put(key, mask);
                cursor++;
            }
        }
    }

    /** Returns the side bits still waiting. Schedules the pipe's re-check tick when a side would connect now. */
    private byte check(long key, byte mask) {
        if (mask == 0) {
            return 0;
        }
        pipePos.set(key);
        if (!level.isLoaded(pipePos)) {
            return 0; // its chunk unloaded; the chunk-load scan finds it again
        }
        BlockState state = level.getBlockState(pipePos);
        if (!(state.getBlock() instanceof FlowPipeBlock pipe)) {
            return 0;
        }
        for (Direction direction : DIRECTIONS) {
            int bit = 1 << direction.get3DDataValue();
            if ((mask & bit) == 0) {
                continue;
            }
            neighborPos.setWithOffset(pipePos, direction);
            if (!level.isLoaded(neighborPos)) {
                continue; // keep waiting
            }
            BlockState neighborState = level.getBlockState(neighborPos);
            if (state.getValue(FlowPipeBlock.propertyFor(direction)) != SideState.NONE
                    || !neighborState.hasBlockEntity() || neighborState.getBlock() instanceof FlowPipeBlock) {
                mask &= ~bit;
            } else if (pipe.connectsToBlock(level, neighborPos, neighborState, direction)) {
                level.scheduleTick(pipePos.immutable(), pipe, 1); // the tick recomputes the arms
                mask &= ~bit;
            }
        }
        return mask;
    }
}
