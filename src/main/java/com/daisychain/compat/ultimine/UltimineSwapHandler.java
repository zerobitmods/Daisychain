package com.daisychain.compat.ultimine;

import com.daisychain.block.EnergyPipeBlock;
import dev.ftb.mods.ftbultimine.api.rightclick.RightClickHandler;
import dev.ftb.mods.ftbultimine.api.shape.ShapeContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;

/**
 * Step 98 (zerobit): hold the Ultimine key and right-click an energy pipe with a higher-tier energy pipe: every energy
 * pipe in the Ultimine selection is swapped in place, the same as sneak + right-click does for one (settings kept, one
 * pipe used each, the old pipes given back, stops when you run out). Ultimine calls this on the server only; returning 0
 * leaves the click to Ultimine's other handlers and to the normal game.
 */
enum UltimineSwapHandler implements RightClickHandler {
    INSTANCE;

    @Override
    public int handleRightClickBlock(ShapeContext context, InteractionHand hand, Collection<BlockPos> positions) {
        ServerPlayer player = context.player();
        ItemStack stack = player.getItemInHand(hand);
        if (!EnergyPipeBlock.canSwap(context.origState(), stack)) {
            return 0;
        }
        return EnergyPipeBlock.swapMany(player.serverLevel(), player, stack, positions);
    }
}
