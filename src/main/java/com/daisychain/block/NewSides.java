package com.daisychain.block;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Steps 163 / 169: pipe sides that just connected to a block, waiting for the pipe's next tick to decide whether they
 * start as pull (input) sides: a redstone pipe next to a lever, an energy pipe next to a generator. Server thread only;
 * an entry lives one tick.
 */
public final class NewSides {
    private static final Map<ServerLevel, Long2ByteOpenHashMap> BY_LEVEL = new IdentityHashMap<>();

    private NewSides() {
    }

    static void note(ServerLevel level, BlockPos pos, Direction side) {
        Long2ByteOpenHashMap sides = BY_LEVEL.computeIfAbsent(level, l -> new Long2ByteOpenHashMap());
        long key = pos.asLong();
        sides.put(key, (byte) (sides.get(key) | (1 << side.get3DDataValue())));
    }

    /** Takes the sides noted for the pipe at pos (0 if none). */
    static int take(ServerLevel level, BlockPos pos) {
        Long2ByteOpenHashMap sides = BY_LEVEL.get(level);
        return sides == null || sides.isEmpty() ? 0 : sides.remove(pos.asLong());
    }

    public static void remove(ServerLevel level) {
        BY_LEVEL.remove(level);
    }

    public static void clearAll() {
        BY_LEVEL.clear();
    }
}
