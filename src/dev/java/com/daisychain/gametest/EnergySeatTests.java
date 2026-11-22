package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.network.NoEnergy;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;

/**
 * Step F68 (port of 141): blocks that only stay put on a block offering energy on that face (Powah's Energizing Rod
 * checks its base face when placed and again on every neighbor update, and drops off if it fails) can sit on energy and
 * universal pipes, and get fed. The pipe has to offer energy on every face, bare or not, and keep offering it whatever
 * that side is set to. On Forge every energy and universal pipe has a block entity for this, even without settings.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EnergySeatTests {
    private static final String EMPTY = "empty";
    private static final BlockPos A = new BlockPos(1, 1, 2);

    private EnergySeatTests() {
    }

    /**
     * A bare pipe (no settings) offers energy on all six faces, and nothing without a face. Also for a pipe from an
     * older world, which has no block entity yet: it gets one the first time something asks.
     */
    @GameTest(template = EMPTY, timeoutTicks = 20)
    public static void barePipesOfferEnergyOnEveryFace(GameTestHelper helper) {
        BlockPos energy = new BlockPos(2, 1, 2);
        BlockPos universal = new BlockPos(5, 1, 2);
        helper.setBlock(energy, ModBlocks.energyPipes()[0].defaultBlockState());
        helper.setBlock(universal, ModBlocks.UNIVERSAL_PIPE.get().defaultBlockState());
        helper.runAfterDelay(1, () -> {
            for (BlockPos pipe : new BlockPos[]{energy, universal}) {
                BlockPos pos = helper.absolutePos(pipe);
                helper.assertTrue(BasePipeBlock.getData(helper.getLevel(), pos, helper.getLevel().getBlockState(pos))
                        == null, "Pipe at " + pipe + " isn't bare");
                helper.getLevel().removeBlockEntity(pos); // as in a world saved before this step
                for (Direction side : Direction.values()) {
                    IEnergyStorage storage = energyAt(helper, pos, side);
                    helper.assertTrue(storage != null, "Pipe at " + pipe + " offers no energy on its " + side
                            + " face, so nothing can sit there");
                    helper.assertTrue(storage.receiveEnergy(1000, true) == 0 && storage.extractEnergy(1000, true) == 0,
                            "A plain side of the pipe at " + pipe + " must take and give nothing");
                }
                helper.assertTrue(energyAt(helper, pos, null) == null,
                        "Asked without a face, the pipe at " + pipe + " must offer nothing");
            }
            helper.succeed();
        });
    }

    /** Energy pipe: see seatStaysAndIsFed. */
    @GameTest(template = EMPTY, timeoutTicks = 140)
    public static void seatOnEnergyPipe(GameTestHelper helper) {
        seatStaysAndIsFed(helper, ModBlocks.energyPipes()[0]);
    }

    /** Universal pipe: see seatStaysAndIsFed. */
    @GameTest(template = EMPTY, timeoutTicks = 140)
    public static void seatOnUniversalPipe(GameTestHelper helper) {
        seatStaysAndIsFed(helper, ModBlocks.UNIVERSAL_PIPE.get());
    }

    /**
     * Stone (stands in for a pushing block) | pipe pulling from it | pipe with a one-face sink (OneFaceSink) on top,
     * taking energy only through its bottom, like a rod on its base. The top face of the second pipe offers energy
     * before the sink is there (placing it), while the sink sits there (insert side), with that side Off and after
     * it's back; the sink is fed except while Off.
     */
    private static void seatStaysAndIsFed(GameTestHelper helper, Block pipe) {
        BlockPos first = A.east();
        BlockPos second = A.east(2);
        BlockPos seat = second.above();
        BlockPos secondPos = helper.absolutePos(second);
        helper.setBlock(A, Blocks.STONE.defaultBlockState());
        helper.setBlock(first, pipe.defaultBlockState());
        helper.setBlock(second, pipe.defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(first));
        BasePipeBlock.refreshArms(helper.getLevel(), secondPos);
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(first),
                1 << Direction.WEST.get3DDataValue());
        assertSeat(helper, secondPos, "before the block was placed on it");
        helper.setBlock(seat, Blocks.JUKEBOX.defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), secondPos);
        for (int t = 2; t <= 100; t++) {
            int tick = t;
            helper.runAfterDelay(t, () -> {
                IEnergyStorage input = energyAt(helper, helper.absolutePos(first), Direction.WEST);
                if (input != null) {
                    input.receiveEnergy(1000, false);
                }
                if (tick % 5 == 0) {
                    assertSeat(helper, secondPos, "at tick " + tick);
                }
            });
        }
        long[] mark = new long[1];
        @SuppressWarnings("unchecked")
        LazyOptional<IEnergyStorage>[] held = new LazyOptional[1];
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(sink(helper, seat).received > 0, "The block on the pipe got no energy");
            BasePipeBlock.setSidesForTest(helper.getLevel(), secondPos, 0, 1 << Direction.UP.get3DDataValue());
            assertSeat(helper, secondPos, "right after its side was set Off");
        });
        helper.runAfterDelay(32, () -> mark[0] = sink(helper, seat).received);
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(sink(helper, seat).received == mark[0], "The block got energy through an Off side");
            BasePipeBlock.setSidesForTest(helper.getLevel(), secondPos, 0, 0);
            assertSeat(helper, secondPos, "right after its side was turned back on");
        });
        helper.runAfterDelay(101, () -> {
            helper.assertTrue(sink(helper, seat).received > mark[0], "No energy after the side was turned back on");
            // Review: the face the block sits on turns into a pull side. It must still offer energy, now the pipe's
            // energy input (and the NoEnergy handed out before is invalidated, so holders ask again).
            held[0] = helper.getLevel().getBlockEntity(secondPos).getCapability(ForgeCapabilities.ENERGY, Direction.UP);
            helper.assertTrue(held[0].resolve().orElse(null) == NoEnergy.INSTANCE,
                    "An insert side should show NoEnergy");
            BasePipeBlock.setSidesForTest(helper.getLevel(), secondPos, 1 << Direction.UP.get3DDataValue(), 0);
            assertSeat(helper, secondPos, "right after its side became a pull side");
            helper.assertTrue(!held[0].isPresent(), "The NoEnergy handed out before wasn't invalidated");
            IEnergyStorage face = energyAt(helper, secondPos, Direction.UP);
            helper.assertTrue(face != null && face != NoEnergy.INSTANCE,
                    "After turning into a pull side, the face still shows NoEnergy (should be the energy input)");
            helper.succeed();
        });
    }

    private static void assertSeat(GameTestHelper helper, BlockPos pipePos, String when) {
        helper.assertTrue(energyAt(helper, pipePos, Direction.UP) != null,
                "The pipe's top face offers no energy " + when + " (a rod there would drop off)");
    }

    @Nullable
    private static IEnergyStorage energyAt(GameTestHelper helper, BlockPos absolutePos, @Nullable Direction side) {
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(absolutePos);
        return blockEntity == null ? null
                : blockEntity.getCapability(ForgeCapabilities.ENERGY, side).resolve().orElse(null);
    }

    private static OneFaceSink sink(GameTestHelper helper, BlockPos pos) {
        return OneFaceSink.of(helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
    }
}
