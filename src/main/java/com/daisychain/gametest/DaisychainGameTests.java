package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.block.SideState;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Step 97 / F25 (zerobit): automatic tests. Each one builds its own setup in an empty 9x4x5 space (structure
 * daisychain:empty), does something, and checks the result. They run with ./gradlew runGameTestServer (no game window;
 * the exit code is the number of failed tests) and on every GitHub build. Only loaded when game tests are enabled, never
 * in a normal game. Positions are relative to the test space; y 1 is the floor row.
 * <p>
 * They check rules and counts (nothing lost or doubled, settings kept); looks are checked by hand with
 * /daisychain testbench.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DaisychainGameTests {
    private static final String EMPTY = "empty";
    private static final BlockPos A = new BlockPos(1, 1, 2);
    private static final BlockPos B = new BlockPos(5, 1, 2);
    private static final int TOTAL = 192;

    private DaisychainGameTests() {
    }

    // ---------------------------------------------------------------- items

    /** Chest -> 3 pipes -> chest: everything arrives, and there is never more than there was. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void itemsMoveWithoutLoss(GameTestHelper helper) {
        itemLine(helper, 3);
        helper.onEachTick(() -> {
            int total = count(helper, A) + count(helper, B);
            if (total > TOTAL) {
                helper.fail("Items doubled: " + total + " (should be " + TOTAL + ")");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(count(helper, A) == 0, "Source still has " + count(helper, A));
            helper.assertTrue(count(helper, B) == TOTAL, "Destination has " + count(helper, B));
        });
    }

    /** The destination is full: nothing leaves the source, and nothing is lost. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void fullDestinationKeepsItems(GameTestHelper helper) {
        chest(helper, A, new ItemStack(Items.DIRT, 64));
        ItemStack[] stone = new ItemStack[27];
        for (int i = 0; i < stone.length; i++) {
            stone[i] = new ItemStack(Items.STONE, 64);
        }
        chest(helper, B, stone);
        line(helper, A.east(), 3, ModBlocks.ITEM_PIPE.get());
        pull(helper, A.east(), Direction.WEST, 3);
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(count(helper, A) == 64, "Source has " + count(helper, A) + " dirt (should be 64)");
            helper.assertTrue(count(helper, B) == 27 * 64, "Destination changed: " + count(helper, B));
            helper.succeed();
        });
    }

    /** A pipe in the middle is broken while items move: the two chests still hold every item. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void breakPipeMidTransferLosesNothing(GameTestHelper helper) {
        itemLine(helper, 1); // Iron: 16 items every 10 ticks, so the break lands mid-way
        helper.runAfterDelay(25, () -> helper.destroyBlock(A.east(2)));
        helper.runAfterDelay(80, () -> {
            int total = count(helper, A) + count(helper, B);
            helper.assertTrue(total == TOTAL, "Chests hold " + total + " items (should be " + TOTAL + ")");
            helper.assertTrue(count(helper, B) > 0, "Nothing moved before the break");
            helper.assertTrue(count(helper, A) > 0, "Everything moved before the break; the test proved nothing");
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- fluids

    /** A full water cauldron empties into the empty one through fluid pipes (whole buckets, Iron upgrade). */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void waterMovesBetweenCauldrons(GameTestHelper helper) {
        helper.setBlock(A, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
        helper.setBlock(B, Blocks.CAULDRON.defaultBlockState());
        line(helper, A.east(), 3, ModBlocks.FLUID_PIPE.get());
        pull(helper, A.east(), Direction.WEST, 1);
        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.CAULDRON, A);
            helper.assertBlockPresent(Blocks.WATER_CAULDRON, B);
        });
    }

    // ---------------------------------------------------------------- energy tier swap (step 93)

    /** Swapping a basic energy pipe to Gold keeps its arms, settings and old upgrade; one pipe out, the old one back. */
    @GameTest(template = EMPTY)
    public static void tierSwapKeepsSettings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pipe = A.east();
        line(helper, pipe, 2, ModBlocks.energyPipes()[0]);
        pull(helper, pipe, Direction.WEST, 0);
        PipeDataBlockEntity data = data(helper, pipe);
        data.applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_BATCH, 1234, PipeKind.ENERGY_RES);
        data.setUpgradeLevel(Direction.WEST, 2);

        Player player = helper.makeMockPlayer();
        ItemStack gold = new ItemStack(ModBlocks.energyPipes()[2], 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, gold);
        BlockPos at = helper.absolutePos(pipe);
        EnergyPipeBlock.swapInPlace(level, at, level.getBlockState(at), player, gold);

        BlockState after = level.getBlockState(at);
        helper.assertTrue(after.getBlock() == ModBlocks.energyPipes()[2], "Pipe is " + after.getBlock() + ", not Gold");
        helper.assertTrue(after.getValue(FlowPipeBlock.propertyFor(Direction.WEST)) == SideState.EXTRACT,
                "The pull side was lost");
        helper.assertTrue(after.getValue(FlowPipeBlock.propertyFor(Direction.EAST)) != SideState.NONE,
                "The link to the next pipe was lost");
        PipeDataBlockEntity moved = data(helper, pipe);
        helper.assertTrue(moved.batch(Direction.WEST, PipeKind.ENERGY_RES) == 1234,
                "Batch is " + moved.batch(Direction.WEST, PipeKind.ENERGY_RES) + " (should be 1234)");
        helper.assertTrue(moved.upgradeLevel(Direction.WEST) == 2, "The old upgrade was not kept");
        helper.assertTrue(gold.getCount() == 1, "Gold pipes left: " + gold.getCount() + " (should be 1)");
        helper.assertTrue(player.getInventory().countItem(ModBlocks.energyPipes()[0].asItem()) == 1,
                "The old pipe was not given back");
        helper.assertTrue(itemsOnGround(helper) == 0, "Something was dropped on the ground");
        helper.succeed();
    }

    /**
     * Step 98 (Ultimine's swap of a whole selection): three basic pipes, two Gold in hand: two swap, the third stays basic,
     * the hand is empty, two basic pipes come back. A non-pipe block in the list is skipped.
     */
    @GameTest(template = EMPTY)
    public static void swapManyStopsWhenOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = A.east();
        line(helper, first, 3, ModBlocks.energyPipes()[0]);
        helper.setBlock(A, Blocks.STONE.defaultBlockState());
        Player player = helper.makeMockPlayer();
        ItemStack gold = new ItemStack(ModBlocks.energyPipes()[2], 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, gold);
        java.util.List<BlockPos> positions = java.util.List.of(helper.absolutePos(A), helper.absolutePos(first),
                helper.absolutePos(first.east()), helper.absolutePos(first.east(2)));
        int swapped = EnergyPipeBlock.swapMany(level, player, gold, positions);
        helper.assertTrue(swapped == 2, "Swapped " + swapped + " (should be 2)");
        helper.assertBlockPresent(ModBlocks.energyPipes()[2], first);
        helper.assertBlockPresent(ModBlocks.energyPipes()[2], first.east());
        helper.assertBlockPresent(ModBlocks.energyPipes()[0], first.east(2));
        helper.assertBlockPresent(Blocks.STONE, A);
        helper.assertTrue(gold.isEmpty(), "Gold pipes left: " + gold.getCount());
        helper.assertTrue(player.getInventory().countItem(ModBlocks.energyPipes()[0].asItem()) == 2,
                "Old pipes given back: " + player.getInventory().countItem(ModBlocks.energyPipes()[0].asItem()));
        helper.succeed();
    }

    /** Only a higher tier swaps. */
    @GameTest(template = EMPTY)
    public static void tierSwapOnlyGoesUp(GameTestHelper helper) {
        Block[] tiers = ModBlocks.energyPipes();
        BlockState gold = tiers[2].defaultBlockState();
        helper.assertTrue(EnergyPipeBlock.canSwap(tiers[0].defaultBlockState(), new ItemStack(tiers[2])), "Up refused");
        helper.assertTrue(!EnergyPipeBlock.canSwap(gold, new ItemStack(tiers[2])), "Same tier swapped");
        helper.assertTrue(!EnergyPipeBlock.canSwap(gold, new ItemStack(tiers[0])), "Down swapped");
        helper.assertTrue(!EnergyPipeBlock.canSwap(ModBlocks.ITEM_PIPE.get().defaultBlockState(), new ItemStack(tiers[2])),
                "An item pipe swapped");
        helper.succeed();
    }

    // ---------------------------------------------------------------- pull caps (step 95)

    /** A plain click on a pull cap with a pipe opens the settings (no placing); a sneak-click lets vanilla place. */
    @GameTest(template = EMPTY)
    public static void pullCapClick(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(4, 1, 2);
        line(helper, pipe, 1, ModBlocks.ITEM_PIPE.get());
        pull(helper, pipe, Direction.SOUTH, 0);
        Player player = helper.makeMockPlayer();
        ItemStack pipes = new ItemStack(ModBlocks.ITEM_PIPE.get(), 8);
        player.setItemInHand(InteractionHand.MAIN_HAND, pipes);

        helper.assertTrue(click(helper, pipe, capHit(helper, pipe, Direction.SOUTH), player, pipes).consumesAction(),
                "Plain click on the pull cap didn't open the settings");
        player.setShiftKeyDown(true);
        helper.assertTrue(click(helper, pipe, capHit(helper, pipe, Direction.SOUTH), player, pipes)
                == InteractionResult.PASS, "Sneak click on the pull cap didn't place");
        helper.assertTrue(pipes.getCount() == 8, "The pipe click used up items");
        helper.succeed();
    }

    /** Sneak-clicking a pull cap with a higher tier places (no swap); sneak-clicking the middle swaps. */
    @GameTest(template = EMPTY)
    public static void swapNotOnPullCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pipe = new BlockPos(4, 1, 2);
        line(helper, pipe, 1, ModBlocks.energyPipes()[0]);
        pull(helper, pipe, Direction.SOUTH, 0);
        Player player = helper.makeMockPlayer();
        ItemStack gold = new ItemStack(ModBlocks.energyPipes()[2], 4);
        player.setItemInHand(InteractionHand.MAIN_HAND, gold);
        player.setShiftKeyDown(true);
        BlockPos at = helper.absolutePos(pipe);

        click(helper, pipe, capHit(helper, pipe, Direction.SOUTH), player, gold);
        helper.assertTrue(level.getBlockState(at).getBlock() == ModBlocks.energyPipes()[0], "Swapped on the pull cap");
        BlockHitResult middle = new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.18, 0), Direction.UP, at, false);
        click(helper, pipe, middle, player, gold);
        helper.assertTrue(level.getBlockState(at).getBlock() == ModBlocks.energyPipes()[2], "Middle didn't swap");
        helper.succeed();
    }

    // ---------------------------------------------------------------- energy speed (step 100)

    /** A basic energy line delivers the basic speed per tick (default 20,000 FE/t), no more and no less. */
    @GameTest(template = EMPTY, timeoutTicks = 120)
    public static void energyBasicSpeed(GameTestHelper helper) {
        energySpeed(helper, new int[]{0, 0, 0}, 0);
    }

    /** A Gold energy line delivers the Gold speed. */
    @GameTest(template = EMPTY, timeoutTicks = 120)
    public static void energyGoldSpeed(GameTestHelper helper) {
        energySpeed(helper, new int[]{2, 2, 2}, 2);
    }

    /** Route speed: one basic pipe in the middle of a Gold line limits the machine past it to the basic speed. */
    @GameTest(template = EMPTY, timeoutTicks = 120)
    public static void energySlowPipeLimitsRoute(GameTestHelper helper) {
        energySpeed(helper, new int[]{2, 0, 2}, 0);
    }

    /**
     * Daylight detector (endless source) -> energy pipes of these tiers -> lectern (counting sink, step 100). After a
     * warm-up, the FE arriving over 40 ticks must match the expected tier's speed (within one transfer).
     */
    private static void energySpeed(GameTestHelper helper, int[] tiers, int expectedTier) {
        helper.setBlock(A, Blocks.DAYLIGHT_DETECTOR.defaultBlockState());
        BlockPos sink = A.east(tiers.length + 1);
        helper.setBlock(sink, Blocks.LECTERN.defaultBlockState());
        for (int i = 0; i < tiers.length; i++) {
            helper.setBlock(A.east(i + 1), ModBlocks.energyPipes()[tiers[i]].defaultBlockState());
        }
        for (int i = 0; i < tiers.length; i++) {
            FlowPipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(A.east(i + 1)));
        }
        pull(helper, A.east(), Direction.WEST, 0);
        long[] start = new long[1];
        helper.runAfterDelay(20, () -> start[0] = received(helper, sink));
        helper.runAfterDelay(60, () -> {
            long got = received(helper, sink) - start[0];
            long perTransfer = com.daisychain.config.UpgradeSpeeds.energyFe(expectedTier);
            long transfers = 40 / Math.max(1, com.daisychain.config.UpgradeSpeeds.energyInterval(expectedTier));
            helper.assertTrue(got >= (transfers - 1) * perTransfer && got <= (transfers + 1) * perTransfer,
                    "Got " + got + " FE in 40 ticks, expected about " + transfers * perTransfer);
            helper.succeed();
        });
    }

    private static long received(GameTestHelper helper, BlockPos lectern) {
        return com.daisychain.command.StressTestEnergy.received(
                helper.getLevel().getBlockEntity(helper.absolutePos(lectern)));
    }

    // ---------------------------------------------------------------- odd data (step 100)

    /**
     * What a building tool's copy/paste can leave behind: pipes whose block state says "pull side" or "has settings"
     * with no settings stored. Nothing may crash, and no item may be lost or doubled.
     */
    @GameTest(template = EMPTY, timeoutTicks = 120)
    public static void pastedPipesWithoutSettings(GameTestHelper helper) {
        chest(helper, A, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 64),
                new ItemStack(Items.SAND, 64));
        chest(helper, B);
        Block pipe = ModBlocks.ITEM_PIPE.get();
        BlockState pull = pipe.defaultBlockState()
                .setValue(FlowPipeBlock.propertyFor(Direction.WEST), SideState.EXTRACT)
                .setValue(FlowPipeBlock.propertyFor(Direction.EAST), SideState.CONNECTED);
        BlockState middle = pipe.defaultBlockState()
                .setValue(FlowPipeBlock.propertyFor(Direction.WEST), SideState.CONNECTED)
                .setValue(FlowPipeBlock.propertyFor(Direction.EAST), SideState.CONNECTED)
                .setValue(FlowPipeBlock.HAS_DATA, true);
        helper.setBlock(A.east(), pull);
        helper.setBlock(A.east(2), middle);
        helper.setBlock(A.east(3), pull.setValue(FlowPipeBlock.HAS_DATA, true));
        helper.runAfterDelay(20, () -> helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(A.east()))
                .getValue(FlowPipeBlock.propertyFor(Direction.WEST)) != SideState.EXTRACT,
                "A pasted pull side with no settings was not cleaned up (step 101)"));
        helper.runAfterDelay(40, () -> {
            for (int i = 1; i <= 3; i++) {
                FlowPipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(A.east(i)));
            }
        });
        helper.runAfterDelay(100, () -> {
            int total = count(helper, A) + count(helper, B);
            helper.assertTrue(total == TOTAL, "Chests hold " + total + " items (should be " + TOTAL + ")");
            helper.succeed();
        });
    }

    /** Broken saved settings (out-of-range numbers, wrong array sizes, junk filters) load without crashing. */
    @GameTest(template = EMPTY)
    public static void brokenSettingsLoadSafely(GameTestHelper helper) {
        BlockPos pos = A.east();
        line(helper, pos, 1, ModBlocks.ITEM_PIPE.get());
        pull(helper, pos, Direction.WEST, 0);
        PipeDataBlockEntity data = data(helper, pos);
        net.minecraft.nbt.CompoundTag junk = new net.minecraft.nbt.CompoundTag();
        junk.putByte("Extract", (byte) 127);
        junk.putByte("Disconnect", (byte) -1);
        junk.putByteArray("Upgrades", new byte[]{99, -5, 7});
        junk.putByteArray("Redstone", new byte[]{120, 120, 120, 120, 120, 120, 120, 120, 120});
        junk.putByteArray("Distribution", new byte[]{-1});
        junk.putIntArray("Batch", new int[100]);
        junk.putIntArray("Priority", new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
        junk.putIntArray("Keep", new int[]{-1, -1, -1});
        junk.putByte("View", (byte) 77);
        net.minecraft.nbt.ListTag filters = new net.minecraft.nbt.ListTag();
        net.minecraft.nbt.CompoundTag badFilter = new net.minecraft.nbt.CompoundTag();
        badFilter.putByte("Side", (byte) 42);
        badFilter.putByte("Resource", (byte) 9);
        badFilter.putString("Entries", "not a list");
        filters.add(badFilter);
        junk.put("Filters", filters);
        data.load(junk);
        for (Direction side : Direction.values()) {
            int level = data.upgradeLevel(side);
            helper.assertTrue(level >= 0 && level <= 5, "Upgrade level " + level + " on " + side);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    /** Chest A (64 cobble, dirt, sand) -> 3 item pipes pulling from A with this upgrade -> empty chest B. */
    private static void itemLine(GameTestHelper helper, int upgrade) {
        chest(helper, A, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 64),
                new ItemStack(Items.SAND, 64));
        chest(helper, B);
        line(helper, A.east(), 3, ModBlocks.ITEM_PIPE.get());
        pull(helper, A.east(), Direction.WEST, upgrade);
    }

    private static void line(GameTestHelper helper, BlockPos first, int length, Block pipe) {
        for (int i = 0; i < length; i++) {
            helper.setBlock(first.east(i), pipe.defaultBlockState());
        }
        for (int i = 0; i < length; i++) {
            FlowPipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(first.east(i)));
        }
    }

    private static void pull(GameTestHelper helper, BlockPos pipe, Direction side, int upgrade) {
        FlowPipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(pipe), 1 << side.get3DDataValue());
        if (upgrade > 0) {
            data(helper, pipe).setUpgradeLevel(side, upgrade);
        }
    }

    private static PipeDataBlockEntity data(GameTestHelper helper, BlockPos pipe) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pipe)) instanceof PipeDataBlockEntity data) {
            return data;
        }
        throw new IllegalStateException("No pipe settings at " + pipe);
    }

    private static void chest(GameTestHelper helper, BlockPos pos, ItemStack... contents) {
        helper.setBlock(pos, Blocks.CHEST.defaultBlockState());
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < contents.length; i++) {
                chest.setItem(i, contents[i]);
            }
        }
    }

    private static int count(GameTestHelper helper, BlockPos pos) {
        int total = 0;
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < chest.getContainerSize(); i++) {
                total += chest.getItem(i).getCount();
            }
        }
        return total;
    }

    private static int itemsOnGround(GameTestHelper helper) {
        // Only this test's own space: tests run side by side, and another test may drop something next door.
        AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(9, 4, 5))));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).size();
    }

    /** A click on the far end of a side's arm (where a pull cap sits). */
    private static BlockHitResult capHit(GameTestHelper helper, BlockPos pipe, Direction side) {
        BlockPos at = helper.absolutePos(pipe);
        Vec3 location = Vec3.atCenterOf(at).add(side.getStepX() * 0.45, side.getStepY() * 0.45, side.getStepZ() * 0.45);
        return new BlockHitResult(location, side, at, false);
    }

    /** Forge 1.20.1: the stack must be in the player's main hand (Block.use reads it from there). */
    private static InteractionResult click(GameTestHelper helper, BlockPos pipe, BlockHitResult hit, Player player,
                                           ItemStack stack) {
        BlockPos at = helper.absolutePos(pipe);
        return helper.getLevel().getBlockState(at).use(helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
    }
}
