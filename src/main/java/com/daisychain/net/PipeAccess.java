package com.daisychain.net;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Review R3 (2026-11-09): which pipe each player last opened with a click the server allowed.
 * <p>
 * Claim mods and spawn protection stop a click on the server, but the player's own game still opens the "Pipe sides"
 * screen, and its packets used to be accepted from anyone within 8 blocks. Now the server remembers the pipe when its
 * own side of the click really ran (FlowPipeBlock.openScreen), and the packets that change a pipe or open its settings
 * are only accepted for that pipe. Server thread only; never saved; forgotten on logout and when the server stops.
 */
public final class PipeAccess {
    private static final Map<UUID, Grant> GRANTS = new HashMap<>();

    private record Grant(ResourceKey<Level> dimension, long pos) {
    }

    private PipeAccess() {
    }

    /** The server let this player click the pipe at pos. Replaces the player's earlier pipe. */
    public static void grant(ServerPlayer player, BlockPos pos) {
        GRANTS.put(player.getUUID(), new Grant(player.level().dimension(), pos.asLong()));
    }

    /** True if this is the pipe the player last clicked with the server's consent, and they may still touch it. */
    public static boolean allowed(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Grant grant = GRANTS.get(player.getUUID());
        return grant != null && grant.dimension() == level.dimension() && grant.pos() == pos.asLong()
                && level.mayInteract(player, pos);
    }

    public static void remove(UUID player) {
        GRANTS.remove(player);
    }

    public static void clearAll() {
        GRANTS.clear();
    }
}
