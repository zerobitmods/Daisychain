package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.SideState;
import com.daisychain.network.NetworkManager;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Step 161: the redstone pipe's block. What it connects to (redstone parts, the connects tag, other redstone pipes,
 * an output arm toward the other pipe types; not plain blocks), and that it stays out of item / fluid / energy
 * networks (a pull side registers no extractor, no network is built). The signal itself comes in step 162.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RedstonePipeTests {
    private static final String EMPTY = "empty";

    private RedstonePipeTests() {
    }

    private static SideState side(GameTestHelper helper, BlockPos pos, Direction direction) {
        return helper.getLevel().getBlockState(helper.absolutePos(pos)).getValue(BasePipeBlock.propertyFor(direction));
    }

    /**
     * A redstone pipe with a lamp (tag) north, stone south, a redstone block (gives a signal) above, another redstone
     * pipe east and an item pipe west. Placed last, so it reads its neighbors the way a player's placement does.
     */
    @GameTest(template = EMPTY)
    public static void redstonePipeConnects(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(4, 1, 2);
        helper.setBlock(pipe.north(), Blocks.REDSTONE_LAMP.defaultBlockState());
        helper.setBlock(pipe.south(), Blocks.STONE.defaultBlockState());
        helper.setBlock(pipe.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.setBlock(pipe.east(), ModBlocks.REDSTONE_PIPE.get().defaultBlockState());
        helper.setBlock(pipe.west(), ModBlocks.ITEM_PIPE.get().defaultBlockState());
        helper.setBlock(pipe, ModBlocks.REDSTONE_PIPE.get().defaultBlockState());
        ServerLevel level = helper.getLevel();
        for (BlockPos pos : new BlockPos[] {pipe, pipe.east(), pipe.west()}) {
            BasePipeBlock.refreshArms(level, helper.absolutePos(pos));
        }
        helper.assertTrue(side(helper, pipe, Direction.NORTH) == SideState.CONNECTED, "No output arm to the lamp");
        helper.assertTrue(side(helper, pipe, Direction.SOUTH) == SideState.NONE, "Arm into plain stone");
        helper.assertTrue(side(helper, pipe, Direction.UP) == SideState.CONNECTED, "No arm to the redstone block");
        helper.assertTrue(side(helper, pipe, Direction.EAST) == SideState.CONNECTED, "Redstone pipes didn't link");
        helper.assertTrue(side(helper, pipe, Direction.WEST) == SideState.CONNECTED, "No output arm to the item pipe");
        helper.assertTrue(side(helper, pipe.west(), Direction.EAST) == SideState.NONE,
                "The item pipe connected back to the redstone pipe");
        helper.assertTrue(side(helper, pipe, Direction.DOWN) == SideState.NONE, "Arm into the floor");
        helper.succeed();
    }

    /** A pull (input) side on a redstone pipe next to a chest registers no extractor and builds no network. */
    @GameTest(template = EMPTY)
    public static void redstonePullIsNoExtractor(GameTestHelper helper) {
        BlockPos chest = new BlockPos(4, 1, 1);
        BlockPos pipe = new BlockPos(4, 1, 2);
        helper.setBlock(chest, Blocks.CHEST.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.REDSTONE_PIPE.get().defaultBlockState());
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(pipe);
        BasePipeBlock.setPullSidesForTest(level, at, 1 << Direction.NORTH.get3DDataValue());
        BlockState state = level.getBlockState(at);
        helper.assertTrue(state.getValue(BasePipeBlock.propertyFor(Direction.NORTH)) == SideState.EXTRACT,
                "Setup: the input side wasn't set");
        NetworkManager manager = NetworkManager.get(level);
        helper.assertFalse(manager.hasExtractor(at), "A redstone pipe's input side registered an extractor");
        helper.assertTrue(manager.getOrBuild(at) == null, "A network was built for a redstone pipe");
        helper.succeed();
    }

    /**
     * A redstone pipe with an input side on a hopper (a block entity: the arm re-check tick and the watch run) and a
     * settings block entity of its own: blocks changing next to it (neighborChanged -> the redstone and wake paths of
     * the item code) for a few ticks must leave it as it was, with no extractor.
     */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void redstonePipeSurvivesNeighborChanges(GameTestHelper helper) {
        BlockPos hopper = new BlockPos(4, 1, 1);
        BlockPos pipe = new BlockPos(4, 1, 2);
        helper.setBlock(hopper, Blocks.HOPPER.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.REDSTONE_PIPE.get().defaultBlockState());
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(pipe);
        BasePipeBlock.setPullSidesForTest(level, at, 1 << Direction.NORTH.get3DDataValue());
        helper.runAfterDelay(2, () -> helper.setBlock(pipe.east(), Blocks.REDSTONE_BLOCK.defaultBlockState()));
        helper.runAfterDelay(4, () -> helper.setBlock(pipe.east(), Blocks.AIR.defaultBlockState()));
        helper.runAfterDelay(6, () -> helper.setBlock(pipe.west(), Blocks.LEVER.defaultBlockState()));
        helper.runAfterDelay(10, () -> {
            BlockState state = level.getBlockState(at);
            helper.assertTrue(state.is(ModBlocks.REDSTONE_PIPE.get()), "The redstone pipe is gone");
            helper.assertTrue(state.getValue(BasePipeBlock.propertyFor(Direction.NORTH)) == SideState.EXTRACT,
                    "The input side on the hopper was lost");
            helper.assertFalse(NetworkManager.get(level).hasExtractor(at), "An extractor appeared");
            helper.succeed();
        });
    }
}
