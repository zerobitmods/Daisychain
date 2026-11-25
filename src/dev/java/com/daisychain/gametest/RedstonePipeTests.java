package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.RedstonePipeBlock;
import com.daisychain.network.RedstoneLines;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import com.daisychain.block.SideState;
import com.daisychain.network.NetworkManager;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
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

    // ---------------------------------------------------------------- step 162: the signal

    /** A row of redstone pipes along x from (2,1,z) to (2 + count - 1,1,z), arms worked out. */
    private static void row(GameTestHelper helper, int z, int count) {
        for (int i = 0; i < count; i++) {
            helper.setBlock(new BlockPos(2 + i, 1, z), ModBlocks.REDSTONE_PIPE.get().defaultBlockState());
        }
        for (int i = 0; i < count; i++) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(new BlockPos(2 + i, 1, z)));
        }
    }

    private static boolean lit(GameTestHelper helper, BlockPos lamp) {
        return helper.getBlockState(lamp).getValue(RedstoneLampBlock.LIT);
    }

    private static void input(GameTestHelper helper, BlockPos pipe, Direction side) {
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(pipe), 1 << side.get3DDataValue());
    }

    /** Redstone block -> input side -> five pipes -> lamp lights; the block goes, the lamp goes out. */
    @GameTest(template = EMPTY, timeoutTicks = 60)
    public static void signalCrossesLine(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 1, 2);
        BlockPos lamp = new BlockPos(7, 1, 2);
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        row(helper, 2, 5);
        input(helper, new BlockPos(2, 1, 2), Direction.WEST);
        helper.startSequence()
                .thenExecute(() -> helper.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertTrue(lit(helper, lamp), "The lamp didn't light"))
                .thenExecute(() -> helper.setBlock(source, Blocks.AIR.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertFalse(lit(helper, lamp), "The lamp stayed lit"))
                .thenSucceed();
    }

    /** An output arm on an item pipe: the item pipe sees a signal (its redstone settings read hasNeighborSignal). */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void itemPipeSeesSignal(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 1, 2);
        BlockPos item = new BlockPos(4, 1, 2);
        helper.setBlock(item, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        row(helper, 2, 2);
        input(helper, new BlockPos(2, 1, 2), Direction.WEST);
        helper.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.succeedWhen(() -> helper.assertTrue(helper.getLevel().hasNeighborSignal(helper.absolutePos(item)),
                "The item pipe sees no signal"));
    }

    /**
     * A line that feeds itself through dust keeps itself on after its source goes (a latch, by design), and goes out
     * when the loop is broken. Pipes x 2..4 at z 2; dust x 2..4 at z 1. x 2's north side is an input (reads the dust),
     * x 3 and x 4 put out into the dust. The source is a redstone block on x 4's east input.
     */
    @GameTest(template = EMPTY, timeoutTicks = 80)
    public static void loopLatches(GameTestHelper helper) {
        BlockPos lamp = new BlockPos(3, 1, 3);
        BlockPos source = new BlockPos(5, 1, 2);
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, 0, 1), Blocks.STONE.defaultBlockState()); // dust needs a block under it
            helper.setBlock(new BlockPos(x, 1, 1), Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        row(helper, 2, 3);
        input(helper, new BlockPos(2, 1, 2), Direction.NORTH);
        input(helper, new BlockPos(4, 1, 2), Direction.EAST);
        helper.startSequence()
                .thenExecute(() -> helper.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertTrue(lit(helper, lamp), "The lamp didn't light"))
                .thenExecute(() -> helper.setBlock(source, Blocks.AIR.defaultBlockState()))
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(lit(helper, lamp), "The loop didn't hold the line on"))
                .thenExecute(() -> helper.setBlock(new BlockPos(3, 1, 1), Blocks.AIR.defaultBlockState()))
                .thenExecute(() -> helper.setBlock(new BlockPos(4, 1, 1), Blocks.AIR.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertFalse(lit(helper, lamp), "The line stayed on with the loop broken"))
                .thenSucceed();
    }

    /** Breaking a pipe in the middle cuts the line: the lamp past the gap goes out. */
    @GameTest(template = EMPTY, timeoutTicks = 60)
    public static void brokenLineGoesOut(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 1, 2);
        BlockPos lamp = new BlockPos(7, 1, 2);
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        helper.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState());
        row(helper, 2, 5);
        input(helper, new BlockPos(2, 1, 2), Direction.WEST);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(lit(helper, lamp), "The lamp didn't light"))
                .thenExecute(() -> helper.destroyBlock(new BlockPos(4, 1, 2)))
                .thenWaitUntil(() -> helper.assertFalse(lit(helper, lamp), "The lamp past the gap stayed lit"))
                .thenSucceed();
    }

    /**
     * No stuck-on after a reload: a pipe saved as on with nothing powering it (as if its input changed while its chunk
     * was unloaded) is switched off when its chunk (re)loads. The lamp next to it goes out too.
     */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void staleOnFixedWhenChunkLoads(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(3, 1, 2);
        BlockPos lamp = new BlockPos(4, 1, 2);
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        row(helper, 2, 2); // x 2..3; x 3 puts out into the lamp
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    ServerLevel level = helper.getLevel();
                    for (BlockPos at : new BlockPos[] {new BlockPos(2, 1, 2), pipe}) {
                        BlockPos abs = helper.absolutePos(at);
                        level.setBlock(abs, level.getBlockState(abs).setValue(RedstonePipeBlock.POWERED, true),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                    level.setBlock(helper.absolutePos(lamp),
                            Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), Block.UPDATE_CLIENTS);
                    RedstoneLines.onChunkEvent(level, ChunkPos.asLong(helper.absolutePos(pipe)), true);
                })
                .thenWaitUntil(() -> helper.assertFalse(helper.getBlockState(pipe).getValue(RedstonePipeBlock.POWERED),
                        "The pipe stayed on after its chunk loaded"))
                .thenWaitUntil(() -> helper.assertFalse(lit(helper, lamp), "The lamp stayed lit"))
                .thenSucceed();
    }

    // ---------------------------------------------------------------- step 163: which way sides start, forced outputs

    /**
     * A pipe put next to a redstone block makes that side an input (a lamp on the other side stays an output); a lever
     * put next to a pipe later does the same. The redstone block then lights the lamp through the pipe.
     */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void sourceSidesStartAsInputs(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(3, 1, 2);
        BlockPos lamp = pipe.east();
        BlockPos other = new BlockPos(6, 1, 2);
        helper.setBlock(pipe.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.REDSTONE_PIPE.get().defaultBlockState());
        helper.setBlock(other, ModBlocks.REDSTONE_PIPE.get().defaultBlockState());
        helper.setBlock(other.north().below(), Blocks.STONE.defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pipe)); // as a player's placement works them out
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(other));
        helper.startSequence()
                .thenExecute(() -> helper.setBlock(other.north(), Blocks.LEVER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)))
                .thenWaitUntil(() -> {
                    helper.assertTrue(side(helper, pipe, Direction.WEST) == SideState.EXTRACT,
                            "The side on the redstone block didn't start as an input");
                    helper.assertTrue(side(helper, pipe, Direction.EAST) == SideState.CONNECTED, "The lamp side isn't an output");
                    helper.assertTrue(side(helper, other, Direction.NORTH) == SideState.EXTRACT,
                            "The side on the new lever didn't become an input");
                })
                .thenWaitUntil(() -> helper.assertTrue(lit(helper, lamp), "The lamp didn't light"))
                .thenSucceed();
    }

    /** Only new connections: a side wrenched back to Output toward a redstone block stays an output after updates. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void wrenchedOutputStays(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(3, 1, 2);
        helper.setBlock(pipe.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.REDSTONE_PIPE.get().defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pipe));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(side(helper, pipe, Direction.WEST) == SideState.EXTRACT,
                        "Setup: the side didn't start as an input"))
                .thenExecute(() -> BasePipeBlock.setSidesForTest(helper.getLevel(), helper.absolutePos(pipe), 0, 0))
                .thenExecute(() -> helper.setBlock(pipe.north(), Blocks.STONE.defaultBlockState()))
                .thenExecute(() -> helper.setBlock(pipe.north(), Blocks.AIR.defaultBlockState()))
                .thenIdle(5)
                .thenExecute(() -> helper.assertTrue(side(helper, pipe, Direction.WEST) == SideState.CONNECTED,
                        "The wrenched output turned back into an input"))
                .thenSucceed();
    }

    /**
     * A side wrenched to Output toward plain stone gets an arm and powers the stone strongly, so a lamp on the stone's
     * far side lights. Pipe x 2 reads a redstone block (west), x 3 puts out into stone at x 4, lamp at x 5.
     */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void forcedOutputPowersBlock(GameTestHelper helper) {
        BlockPos lamp = new BlockPos(5, 1, 2);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.STONE.defaultBlockState());
        helper.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState());
        row(helper, 2, 2);
        input(helper, new BlockPos(2, 1, 2), Direction.WEST);
        BasePipeBlock.setOutputsForTest(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 2)),
                1 << Direction.EAST.get3DDataValue());
        helper.startSequence()
                .thenExecute(() -> helper.assertTrue(side(helper, new BlockPos(3, 1, 2), Direction.EAST) == SideState.CONNECTED,
                        "No output arm into the stone"))
                .thenExecute(() -> helper.setBlock(new BlockPos(1, 1, 2), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertTrue(lit(helper, lamp), "The lamp behind the stone didn't light"))
                .thenSucceed();
    }
}
