package com.daisychain.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Steps 132/133: may this player change this pipe, which they didn't click? (Mass facade click, Settings Copier
 * network paste, copier undo and redo.) Build rights and spawn protection first (FlowPipeBlock.mayChange), then claim
 * mods: FTB Chunks and others guard claims through NeoForge's place and right-click events, not Level.mayInteract, so
 * without this a change could reach into someone else's claim. A place event for the pipe as it stands asks them;
 * cancelled = not allowed here. The pipe itself doesn't change. A pipe the player clicks needs no check here: the
 * claim mod already stops the click itself.
 */
public final class ClaimCheck {
    private ClaimCheck() {
    }

    public static boolean mayChange(ServerLevel level, BlockPos pos, Player player) {
        return FlowPipeBlock.mayChange(level, pos, player)
                && !EventHooks.onBlockPlace(player, BlockSnapshot.create(level.dimension(), level, pos), Direction.UP);
    }
}
