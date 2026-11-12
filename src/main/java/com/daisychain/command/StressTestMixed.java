package com.daisychain.command;

import com.daisychain.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * /daisychain stress mixed (step 115, development game only): the mod-page test in one command, so it is built the
 * same way every time on both versions. 1,100 item, 300 fluid and 300 universal networks (length 8, diamond) around the
 * player: the item block on the north side, fluids and universal side by side south of it, about 170 x 260 blocks,
 * so everything is within about 8 chunks of the player and runs at simulation distance 10 or more.
 * Then /daisychain stress measure takes the readings.
 */
final class StressTestMixed {
    private static final int ITEM_NETWORKS = 1100;
    private static final int SIDE_NETWORKS = 300;
    private static final int LENGTH = 8;
    private static final int UPGRADE = 3; // diamond
    /** Blocks between the item block and the fluid/universal blocks. */
    private static final int GAP = 4;

    private StressTestMixed() {
    }

    static int build(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos feet = BlockPos.containing(source.getPosition());
        int stride = LENGTH + 4;
        int itemRows = StressTest.rowsFor(ITEM_NETWORKS, LENGTH);
        int sideRows = StressTest.rowsFor(SIDE_NETWORKS, LENGTH);
        int sideWidth = ((SIDE_NETWORKS + sideRows - 1) / sideRows) * stride;
        int depth = itemRows * 2 + GAP + sideRows * 2;
        int top = -depth / 2;
        int sideZ = top + itemRows * 2 + GAP + sideRows;

        int[] items = StressTest.buildBlock(level, feet.offset(0, 0, top + itemRows), ITEM_NETWORKS, LENGTH,
                ModBlocks.ITEM_PIPE.get(), false, false, false, false, UPGRADE);
        int[] fluids = StressTest.buildBlock(level, feet.offset(-sideWidth / 2 - 2, 0, sideZ), SIDE_NETWORKS, LENGTH,
                ModBlocks.FLUID_PIPE.get(), true, false, false, false, UPGRADE);
        int[] universal = StressTest.buildBlock(level, feet.offset(sideWidth / 2 + 2, 0, sideZ), SIDE_NETWORKS, LENGTH,
                ModBlocks.UNIVERSAL_PIPE.get(), false, false, false, false, UPGRADE);

        int total = ITEM_NETWORKS + 2 * SIDE_NETWORKS;
        int notTicking = items[2] + fluids[2] + universal[2];
        int width = Math.max(items[0], sideWidth * 2 + 4);
        String message = String.format(Locale.ROOT,
                "Built %,d networks around you (%,d item, %d fluid, %d universal; diamond, length %d), %d x %d blocks, "
                        + "2 below you.",
                total, ITEM_NETWORKS, SIDE_NETWORKS, SIDE_NETWORKS, LENGTH, width, depth);
        source.sendSuccess(() -> Component.literal(message), true);
        if (notTicking == 0) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "All %,d are inside the simulation distance. Stay here and run /daisychain stress measure.", total))
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            StressTest.warnIfNotTicking(source, notTicking, total);
        }
        return total;
    }
}
