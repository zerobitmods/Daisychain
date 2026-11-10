package com.daisychain.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;

/**
 * Review R4 (2026-11-09): "is this chunk really here?".
 * <p>
 * Level.isLoaded and hasChunk only say that the chunk has a ticket to be loaded. While it is still loading (a player
 * just arrived), reading a block there through the level makes the server thread wait until the chunk is done. So the
 * code that walks pipes or scans around chunk loads asks here instead: a chunk counts only if it has its ticket AND is
 * in memory right now, and blocks are then read straight from that chunk. Both parts matter: a chunk can also still be
 * in memory after losing its ticket (on its way out), and reading it through the level would keep it loaded.
 * <p>
 * Never loads a chunk, never waits. A chunk that is still loading simply counts as "not there yet"; its load event
 * comes when it is ready, and the pipes look again then. Server thread only (other threads get "not loaded").
 */
public final class Loaded {
    private Loaded() {
    }

    /** The chunk at these chunk coordinates if it is really loaded, else null. */
    @Nullable
    public static LevelChunk chunk(ServerLevel level, int chunkX, int chunkZ) {
        ServerChunkCache source = level.getChunkSource();
        return source.hasChunk(chunkX, chunkZ) ? source.getChunkNow(chunkX, chunkZ) : null;
    }

    /** True if the block position is inside the world's height and its chunk is really loaded. */
    public static boolean isLoaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos)
                && chunk(level, SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())) != null;
    }

    /** The block state at pos, or null if it is outside the world's height or its chunk isn't really loaded. */
    @Nullable
    public static BlockState stateIfLoaded(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return null;
        }
        LevelChunk chunk = chunk(level, SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk == null ? null : chunk.getBlockState(pos);
    }
}
