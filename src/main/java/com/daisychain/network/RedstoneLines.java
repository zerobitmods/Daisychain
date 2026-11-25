package com.daisychain.network;

import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.RedstonePipeBlock;
import com.daisychain.block.SideState;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Step 162: the redstone pipes of one dimension, as lines (pipes joined by connected arms). A line is on when any of
 * its input (pull) sides reads a signal; then every pipe in it is POWERED and gives full power out of its output
 * (insert) sides. Nothing runs every tick on its own: pipes are marked "dirty" when something may have changed (a
 * neighbor of an input side, a pipe placed / broken / rewired, a chunk loaded or unloaded), and at the end of the tick
 * each dirty line is worked out once. A line's pipes and sides are cached until its shape changes; an input change
 * only re-reads its inputs. Neighbors are told only when the line's on/off really changed, so nothing recurses:
 * anything that marks a line dirty while lines are being worked out waits for the next tick.
 * <p>
 * Never loads chunks. A line only reaches the loaded part of the world; inputs in unloaded chunks count as off, and
 * pipes there keep their saved state until their chunk loads (it is checked again then). Server thread only.
 */
public final class RedstoneLines {
    private static final Map<ServerLevel, RedstoneLines> LINES = new IdentityHashMap<>();
    /** Chunk events that arrived off the server thread: (level, chunk, was a load). Handled at the next tick. */
    private static final Queue<ChunkEvent> PENDING = new ConcurrentLinkedQueue<>();
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Neighbor updates for a strongly powered block: like a repeater, without updates and shape changes elsewhere. */
    private static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<Line> lineByPipe = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<ReferenceArrayList<Line>> linesByChunk = new Long2ObjectOpenHashMap<>();
    private LongOpenHashSet dirty = new LongOpenHashSet();
    private LongOpenHashSet working = new LongOpenHashSet();
    private int stamp;

    private record ChunkEvent(ServerLevel level, long chunk, boolean load) {
    }

    /** One line: its pipes, input sides and output sides (pipe pos + side), and the chunks it touches. */
    static final class Line {
        final long[] pipes;
        final long[] inputs;
        final byte[] inputSides;
        final long[] outputs;
        final byte[] outputSides;
        final long[] chunks;
        boolean valid = true;
        int stamp = -1;

        Line(long[] pipes, long[] inputs, byte[] inputSides, long[] outputs, byte[] outputSides, long[] chunks) {
            this.pipes = pipes;
            this.inputs = inputs;
            this.inputSides = inputSides;
            this.outputs = outputs;
            this.outputSides = outputSides;
            this.chunks = chunks;
        }
    }

    private RedstoneLines(ServerLevel level) {
        this.level = level;
    }

    public static RedstoneLines get(ServerLevel level) {
        return LINES.computeIfAbsent(level, RedstoneLines::new);
    }

    public static void remove(ServerLevel level) {
        LINES.remove(level);
    }

    public static void clearAll() {
        LINES.clear();
        PENDING.clear();
    }

    // ---------------------------------------------------------------- what marks lines dirty

    /** Something next to this pipe changed (it may read a different signal now). */
    public void markDirty(BlockPos pos) {
        dirty.add(pos.asLong());
    }

    /**
     * A redstone pipe was placed, broken or rewired at pos: the lines there and next to it are rebuilt (they may have
     * split or joined), and worked out at the end of the tick.
     */
    public void shapeChanged(BlockPos pos) {
        long packed = pos.asLong();
        invalidateAt(packed);
        dirty.add(packed);
        for (Direction direction : DIRECTIONS) {
            long next = BlockPos.offset(packed, direction);
            invalidateAt(next);
            dirty.add(next);
        }
    }

    /** A chunk loaded or unloaded (or lost / got back its ticket). Any thread. */
    public static void onChunkEvent(ServerLevel level, long chunk, boolean load) {
        if (level.getServer().isSameThread()) {
            get(level).chunkChanged(chunk, load);
        } else {
            PENDING.add(new ChunkEvent(level, chunk, load));
        }
    }

    /** Review R17's ticket crossings: always handled at the end of the tick, never right away. Any thread. */
    public static void queueChunkEvent(ServerLevel level, long chunk, boolean load) {
        PENDING.add(new ChunkEvent(level, chunk, load));
    }

    private void chunkChanged(long chunk, boolean load) {
        ReferenceArrayList<Line> lines = linesByChunk.remove(chunk);
        if (lines != null) {
            for (int i = 0; i < lines.size(); i++) {
                invalidate(lines.get(i)); // its pipes in loaded chunks are worked out again
            }
        }
        if (load) {
            scanChunk(chunk);
        }
    }

    /**
     * A chunk just loaded: its redstone pipes kept the state they were saved with, which may be stale (an input in
     * another chunk changed meanwhile). Marks them dirty. Only sections whose palette holds a redstone pipe are looked
     * through, so a chunk without one costs a few palette checks.
     */
    private void scanChunk(long chunk) {
        int chunkX = ChunkPos.getX(chunk);
        int chunkZ = ChunkPos.getZ(chunk);
        LevelChunk levelChunk = Loaded.chunk(level, chunkX, chunkZ);
        if (levelChunk == null) {
            return;
        }
        LevelChunkSection[] sections = levelChunk.getSections();
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()
                    || !section.getStates().maybeHas(state -> state.getBlock() instanceof RedstonePipeBlock)) {
                continue;
            }
            int baseY = levelChunk.getSectionYFromSectionIndex(s) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).getBlock() instanceof RedstonePipeBlock) {
                            dirty.add(BlockPos.asLong((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z));
                        }
                    }
                }
            }
        }
    }

    private void invalidateAt(long packed) {
        Line line = lineByPipe.get(packed);
        if (line != null) {
            invalidate(line);
        }
    }

    private void invalidate(Line line) {
        if (!line.valid) {
            return;
        }
        line.valid = false;
        for (long pipe : line.pipes) {
            if (lineByPipe.get(pipe) == line) {
                lineByPipe.remove(pipe);
            }
            dirty.add(pipe);
        }
        for (long chunk : line.chunks) {
            ReferenceArrayList<Line> inChunk = linesByChunk.get(chunk);
            if (inChunk != null) {
                inChunk.remove(line);
                if (inChunk.isEmpty()) {
                    linesByChunk.remove(chunk);
                }
            }
        }
    }

    // ---------------------------------------------------------------- the tick

    /** End of every server tick, for every level. Does nothing unless something was marked. */
    public static void tickAll(ServerLevel level) {
        if (!PENDING.isEmpty()) {
            ChunkEvent event;
            while ((event = PENDING.poll()) != null) {
                get(event.level()).chunkChanged(event.chunk(), event.load());
            }
        }
        RedstoneLines lines = LINES.get(level);
        if (lines != null && !lines.dirty.isEmpty()) {
            lines.tick();
        }
    }

    private void tick() {
        // Swap: anything marked while these lines are worked out (our own neighbor updates) waits for the next tick.
        LongOpenHashSet todo = dirty;
        dirty = working;
        working = todo;
        stamp++;
        var iterator = todo.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            Line line = lineByPipe.get(packed);
            if (line == null || !line.valid) {
                BlockState state = Loaded.stateIfLoaded(level, BlockPos.of(packed));
                if (state == null || !(state.getBlock() instanceof RedstonePipeBlock)) {
                    continue;
                }
                line = build(packed);
            }
            if (line.stamp != stamp) {
                line.stamp = stamp;
                apply(line, readInputs(line));
            }
        }
        todo.clear();
    }

    /** True if any input side of the line reads a signal. Inputs in unloaded chunks count as off. */
    private boolean readInputs(Line line) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < line.inputs.length; i++) {
            Direction side = DIRECTIONS[line.inputSides[i]];
            at.set(line.inputs[i]).move(side);
            if (Loaded.isLoaded(level, at) && level.getSignal(at, side) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Sets every loaded pipe of the line to on / off; then tells the blocks on the changed pipes' output sides. */
    private void apply(Line line, boolean on) {
        LongArrayList changed = null;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (long pipe : line.pipes) {
            at.set(pipe);
            BlockState state = Loaded.stateIfLoaded(level, at);
            if (state == null || !(state.getBlock() instanceof RedstonePipeBlock)
                    || state.getValue(RedstonePipeBlock.POWERED) == on) {
                continue;
            }
            level.setBlock(at, state.setValue(RedstonePipeBlock.POWERED, on), SET_FLAGS);
            if (changed == null) {
                changed = new LongArrayList();
            }
            changed.add(pipe);
        }
        if (changed == null) {
            return;
        }
        LongOpenHashSet changedSet = new LongOpenHashSet(changed);
        for (int i = 0; i < line.outputs.length; i++) {
            if (changedSet.contains(line.outputs[i])) {
                notifyOutput(BlockPos.of(line.outputs[i]), DIRECTIONS[line.outputSides[i]]);
            }
        }
    }

    /**
     * The pipe at pipePos changed what it gives out of side: tell the block there, and (as for a repeater, since the
     * pipe powers it strongly) that block's other neighbors. Only loaded positions.
     */
    public static void notifyOutput(ServerLevel level, BlockPos pipePos, Direction side, Block pipeBlock) {
        BlockPos target = pipePos.relative(side);
        if (!Loaded.isLoaded(level, target)) {
            return;
        }
        level.neighborChanged(target, pipeBlock, pipePos);
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction direction : DIRECTIONS) {
            if (direction == side.getOpposite()) {
                continue; // the pipe itself
            }
            next.setWithOffset(target, direction);
            if (Loaded.isLoaded(level, next)) {
                level.neighborChanged(next, pipeBlock, target);
            }
        }
    }

    private void notifyOutput(BlockPos pipePos, Direction side) {
        BlockState state = level.getBlockState(pipePos);
        notifyOutput(level, pipePos, side, state.getBlock());
    }

    // ---------------------------------------------------------------- building a line

    /** Flood fill from a redstone pipe through connected arms to other redstone pipes, loaded chunks only. */
    private Line build(long start) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongArrayList pipes = new LongArrayList();
        LongArrayList inputs = new LongArrayList();
        LongArrayList outputs = new LongArrayList();
        it.unimi.dsi.fastutil.bytes.ByteArrayList inputSides = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
        it.unimi.dsi.fastutil.bytes.ByteArrayList outputSides = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
        LongOpenHashSet chunks = new LongOpenHashSet();
        seen.add(start);
        queue.enqueue(start);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty()) {
            long packed = queue.dequeueLong();
            at.set(packed);
            BlockState state = Loaded.stateIfLoaded(level, at);
            if (state == null || !(state.getBlock() instanceof RedstonePipeBlock)) {
                continue;
            }
            pipes.add(packed);
            chunks.add(ChunkPos.asLong(at));
            for (Direction direction : DIRECTIONS) {
                SideState side = state.getValue(BasePipeBlock.propertyFor(direction));
                if (side == SideState.EXTRACT) {
                    inputs.add(packed);
                    inputSides.add((byte) direction.get3DDataValue());
                    continue;
                }
                if (side != SideState.CONNECTED) {
                    continue;
                }
                next.setWithOffset(at, direction);
                BlockState neighbor = Loaded.stateIfLoaded(level, next);
                if (neighbor == null) {
                    continue; // unloaded: the line stops here (it is checked again when that chunk loads)
                }
                if (neighbor.getBlock() instanceof RedstonePipeBlock) {
                    if (neighbor.getValue(BasePipeBlock.propertyFor(direction.getOpposite())) == SideState.CONNECTED
                            && seen.add(next.asLong())) {
                        queue.enqueue(next.asLong());
                    }
                    continue;
                }
                outputs.add(packed);
                outputSides.add((byte) direction.get3DDataValue());
            }
        }
        Line line = new Line(pipes.toLongArray(), inputs.toLongArray(), inputSides.toByteArray(),
                outputs.toLongArray(), outputSides.toByteArray(), chunks.toLongArray());
        for (long pipe : line.pipes) {
            Line old = lineByPipe.put(pipe, line);
            if (old != null && old != line && old.valid) {
                // A cached line that this one reached (two parts joined, e.g. a chunk between them loaded).
                old.valid = false;
                for (long chunk : old.chunks) {
                    ReferenceArrayList<Line> inChunk = linesByChunk.get(chunk);
                    if (inChunk != null) {
                        inChunk.remove(old);
                    }
                }
            }
        }
        for (long chunk : line.chunks) {
            linesByChunk.computeIfAbsent(chunk, c -> new ReferenceArrayList<>()).add(line);
        }
        return line;
    }

    // ---------------------------------------------------------------- for /daisychain stats and tests

    public int lineCount() {
        int count = 0;
        for (Line line : new java.util.HashSet<>(lineByPipe.values())) {
            if (line.valid) {
                count++;
            }
        }
        return count;
    }
}
