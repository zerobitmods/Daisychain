package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.client.PipeColors;
import com.daisychain.client.PipeTints;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import com.daisychain.config.DaisychainClientConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Step 147: the pipe colour maths. Picking a pipe's default colour by hand draws exactly the default look, the
 * brightest pickable colour is still one a tint can draw, and the config only takes "#rrggbb" or "".
 * Plain maths, no client needed.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PipeColourTests {
    private static final String EMPTY = "empty";

    private PipeColourTests() {
    }

    @GameTest(template = EMPTY)
    public static void colourMaths(GameTestHelper helper) {
        for (int slot = 0; slot < DaisychainClientConfig.COLOUR_KINDS.length; slot++) {
            String kind = DaisychainClientConfig.COLOUR_KINDS[slot];
            helper.assertTrue(PipeTints.tintFor(slot, false, PipeTints.DEFAULT_SURFACE[slot]) == PipeTints.tintFor(slot, false, -1),
                    kind + ": the default body picked by hand doesn't draw the default");
            helper.assertTrue(PipeTints.tintFor(slot, true, PipeTints.DEFAULT_LINE[slot]) == PipeTints.tintFor(slot, true, -1),
                    kind + ": the default line picked by hand doesn't draw the default");
            for (boolean line : new boolean[] {false, true}) {
                int brightest = PipeTints.clamp(slot, line, 0xFFFFFF);
                int cap = PipeTints.cap(slot, line);
                helper.assertTrue(brightest == (cap << 16 | cap << 8 | cap), kind + ": white isn't toned down to the cap");
                // One step darker than the brightest still draws darker: the cap never runs into the tint's 255 limit.
                int below = PipeTints.tintFor(slot, line, brightest - 0x010101);
                int top = PipeTints.tintFor(slot, line, brightest);
                helper.assertTrue((below & 0xFF) < (top & 0xFF), kind + ": the brightest colour is past what a tint draws");
            }
        }
        helper.assertTrue(DaisychainClientConfig.parseColour("#4e5358") == 0x4E5358, "#4e5358 not read");
        helper.assertTrue(DaisychainClientConfig.parseColour("4E5358") == 0x4E5358, "4E5358 not read");
        helper.assertTrue(DaisychainClientConfig.parseColour("") == -1, "empty isn't the default");
        helper.assertTrue(DaisychainClientConfig.parseColour("#12345") == -1, "5 digits taken");
        helper.assertTrue(DaisychainClientConfig.parseColour("#zzzzzz") == -1, "non-hex taken");
        helper.assertTrue(DaisychainClientConfig.parseColour(null) == -1, "null taken");
        helper.succeed();
    }

    /**
     * Step 155: pull and insert rings are the pipe's line colour, exactly as bright as the line, whatever upgrade the
     * side holds: item / fluid / universal pipes their line colour, energy pipes their tier's line colour.
     */
    @GameTest(template = EMPTY)
    public static void ringsMatchLine(GameTestHelper helper) {
        Block[] kinds = {ModBlocks.ITEM_PIPE.get(), ModBlocks.FLUID_PIPE.get(), ModBlocks.UNIVERSAL_PIPE.get()};
        for (int k = 0; k < kinds.length; k++) {
            BlockPos local = new BlockPos(k * 2, 1, 1);
            helper.setBlock(local, kinds[k]);
            BlockPos pos = helper.absolutePos(local);
            BlockState state = helper.getLevel().getBlockState(pos);
            int slot = PipeTints.slot(com.daisychain.block.BasePipeBlock.kindOf(state));
            int line = PipeTints.line(slot);
            for (int side = 0; side < 6; side++) {
                // The line's texture is 255 / headroom bright, the ring's plain white: same colour on screen.
                int ring = PipeColors.color(state, null, null, side);
                for (int shift = 0; shift <= 16; shift += 8) {
                    float lineSeen = (line >> shift & 0xFF) * PipeTints.cap(slot, true) / 255F;
                    helper.assertTrue(Math.abs((ring >> shift & 0xFF) - lineSeen) <= 1.5F,
                            kinds[k] + ": ring " + Integer.toHexString(ring) + " isn't as bright as line "
                                    + Integer.toHexString(line));
                }
                helper.assertTrue(PipeColors.color(state, null, null, 6 + side) == ring, kinds[k] + ": insert ring differs");
            }
            if (helper.getLevel().getBlockEntity(pos) == null) {
                com.daisychain.block.BasePipeBlock.setPullSidesForTest(helper.getLevel(), pos, 1 << Direction.WEST.get3DDataValue());
            }
            if (helper.getLevel().getBlockEntity(pos) instanceof PipeDataBlockEntity data) {
                data.setUpgradeLevel(Direction.WEST, 3);
                data.setUpgradeLevel(Direction.EAST, 2);
                state = helper.getLevel().getBlockState(pos);
                int plain = PipeColors.color(state, null, null, 0);
                helper.assertTrue(PipeColors.color(state, helper.getLevel(), pos, Direction.WEST.get3DDataValue()) == plain,
                        kinds[k] + ": an upgraded pull ring shows the upgrade");
                helper.assertTrue(PipeColors.color(state, helper.getLevel(), pos, 6 + Direction.EAST.get3DDataValue()) == plain,
                        kinds[k] + ": a parked upgrade shows on the insert ring");
            } else {
                helper.fail(kinds[k] + ": no block entity to hold an upgrade");
            }
        }
        Block[] tiers = ModBlocks.energyPipes();
        for (int t = 0; t < tiers.length; t++) {
            BlockState state = tiers[t].defaultBlockState();
            int band = PipeColors.color(state, null, null, PipeColors.BAND_TINT);
            for (int tint = 0; tint < 12; tint++) {
                helper.assertTrue(PipeColors.color(state, null, null, tint) == band, tiers[t] + ": ring " + tint
                        + " isn't the line colour");
            }
        }
        helper.succeed();
    }
}
