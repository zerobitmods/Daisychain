package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.SideState;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Step 169: an energy pipe side that newly connects to a block that only gives energy (a daylight detector here, see
 * StressTestEnergy) starts as a pull side; a block that only takes (a lectern) or does both (a smoker, TwoWayBattery)
 * stays an insert side; a side wrenched back to insert stays one.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EnergyStartTests {
    private static final String EMPTY = "empty";

    private EnergyStartTests() {
    }

    private static SideState side(GameTestHelper helper, BlockPos pos, Direction direction) {
        return helper.getBlockState(pos).getValue(BasePipeBlock.propertyFor(direction));
    }

    /** Pipe put between a generator (west), a machine (east) and a battery (north): only the generator side pulls. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void generatorSideStartsAsPull(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(3, 1, 2);
        helper.setBlock(pipe.west(), Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        helper.setBlock(pipe.east(), Blocks.LECTERN.defaultBlockState());
        helper.setBlock(pipe.north(), Blocks.SMOKER.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.ENERGY_PIPE.get().defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pipe)); // as a player's placement works them out
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(side(helper, pipe, Direction.WEST) == SideState.EXTRACT,
                        "The generator side didn't start as a pull side"))
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertTrue(side(helper, pipe, Direction.EAST) == SideState.CONNECTED, "The machine side isn't insert");
                    helper.assertTrue(side(helper, pipe, Direction.NORTH) == SideState.CONNECTED, "The battery side isn't insert");
                })
                .thenSucceed();
    }

    /**
     * A generator that says it takes energy too but refuses it (south, a blast furnace) starts as pull; a full battery
     * (up, a furnace) can't be told apart from one, so it stays insert.
     */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void refusingGeneratorPullsFullBatteryDoesnt(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(3, 1, 2);
        helper.setBlock(pipe.south(), Blocks.BLAST_FURNACE.defaultBlockState());
        helper.setBlock(pipe.above(), Blocks.FURNACE.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.ENERGY_PIPE.get().defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pipe));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(side(helper, pipe, Direction.SOUTH) == SideState.EXTRACT,
                        "The generator that refuses energy didn't start as a pull side"))
                .thenIdle(3)
                .thenExecute(() -> helper.assertTrue(side(helper, pipe, Direction.UP) == SideState.CONNECTED,
                        "The full battery side isn't insert"))
                .thenSucceed();
    }

    /** A generator put next to a pipe later: that side becomes a pull side too. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void generatorPlacedLaterPulls(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(3, 1, 2);
        helper.setBlock(pipe, ModBlocks.ENERGY_PIPE.get().defaultBlockState());
        helper.setBlock(pipe.east(), ModBlocks.ENERGY_PIPE.get().defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pipe));
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> helper.setBlock(pipe.west(), Blocks.DAYLIGHT_DETECTOR.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertTrue(side(helper, pipe, Direction.WEST) == SideState.EXTRACT,
                        "The side on the new generator didn't become a pull side"))
                .thenSucceed();
    }

    /** Only new connections: a generator side wrenched back to insert stays insert through neighbor updates. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void wrenchedInsertStays(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(3, 1, 2);
        helper.setBlock(pipe.west(), Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.ENERGY_PIPE.get().defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pipe));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(side(helper, pipe, Direction.WEST) == SideState.EXTRACT,
                        "Setup: the generator side didn't start as a pull side"))
                .thenExecute(() -> BasePipeBlock.setSidesForTest(helper.getLevel(), helper.absolutePos(pipe), 0, 0))
                .thenExecute(() -> helper.setBlock(pipe.north(), Blocks.STONE.defaultBlockState()))
                .thenExecute(() -> helper.setBlock(pipe.north(), Blocks.AIR.defaultBlockState()))
                .thenIdle(5)
                .thenExecute(() -> helper.assertTrue(side(helper, pipe, Direction.WEST) == SideState.CONNECTED,
                        "The insert side turned back into a pull side"))
                .thenSucceed();
    }
}
