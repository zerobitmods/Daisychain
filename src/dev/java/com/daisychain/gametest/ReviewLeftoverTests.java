package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.block.SideState;
import com.daisychain.network.NetworkManager;
import com.daisychain.network.PipeNetwork;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import javax.annotation.Nullable;

/**
 * Steps 117+ (zerobit, 2026-11-13): tests for the review leftovers (R13 second half, R12, R10) and the energy split.
 * Same rules as DaisychainGameTests: each test builds its own setup in the empty 9x4x5 space; y 1 is the floor row.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReviewLeftoverTests {
    private static final String EMPTY = "empty";
    private static final BlockPos A = new BlockPos(1, 1, 2);

    private ReviewLeftoverTests() {
    }

    // ---------------------------------------------------------------- step 117 (review R13, second half)

    /**
     * Chest A -> pipes x 2..4 -> chest B (east end). Chest C sits north of the first pipe, so Nearest sends there
     * first. Raising B's insert priority must switch the items to B without rebuilding the network (same object).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void insertPriorityUpdatesInPlace(GameTestHelper helper) {
        BlockPos b = A.east(4);
        BlockPos c = A.east().north();
        BlockPos last = A.east(3);
        chest(helper, A, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 64),
                new ItemStack(Items.SAND, 64));
        chest(helper, b);
        chest(helper, c);
        for (int i = 1; i <= 3; i++) {
            helper.setBlock(A.east(i), ModBlocks.ITEM_PIPE.get().defaultBlockState());
        }
        for (int i = 1; i <= 3; i++) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(A.east(i)));
        }
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(A.east()), 1 << Direction.WEST.get3DDataValue());
        data(helper, A.east()).setUpgradeLevel(Direction.WEST, 1); // iron: 16 every 10 ticks
        BasePipeBlock.ensureData(helper.getLevel(), helper.absolutePos(last)); // B's insert side gets settings storage
        PipeNetwork[] before = new PipeNetwork[1];
        int[] cBefore = new int[1];
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(count(helper, c) > 0, "Nearest chest C got nothing before the change");
            helper.assertTrue(count(helper, b) == 0, "Far chest B got " + count(helper, b) + " before the change");
            before[0] = network(helper, last);
            cBefore[0] = count(helper, c);
            data(helper, last).applySetting(Direction.EAST, PipeDataBlockEntity.SETTING_PRIORITY, 5, PipeKind.ITEMS);
        });
        helper.runAfterDelay(90, () -> {
            helper.assertTrue(network(helper, last) == before[0], "The priority change rebuilt the network");
            helper.assertTrue(count(helper, b) > 0, "Chest B (priority 5) got nothing after the change");
            helper.assertTrue(count(helper, c) <= cBefore[0] + 16,
                    "Chest C kept filling: " + cBefore[0] + " -> " + count(helper, c));
            int total = count(helper, A) + count(helper, b) + count(helper, c);
            helper.assertTrue(total == 192, "Chests hold " + total + " items (should be 192)");
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- step 118 (review R12)

    /**
     * A player places a pipe where a neighbor pipe's pull side points, and a protection mod cancels the place event:
     * the neighbor keeps its pull side and its upgrade, and nothing drops.
     */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void cancelledPlacementLeavesNeighbor(GameTestHelper helper) {
        placeNextToPullSide(helper, true);
    }

    /** The same placement, not cancelled: the neighbor stops pulling from the new pipe and its upgrade drops. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void placementTakesNeighborPull(GameTestHelper helper) {
        placeNextToPullSide(helper, false);
    }

    // ---------------------------------------------------------------- step F52 (port of 124)

    /** /setblock (no player, no place event) where a pull side points: the neighbor stops pulling, one upgrade drops. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void commandPlacementTakesNeighborPull(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(2, 1, 2);
        BlockPos spot = pipe.east();
        helper.setBlock(pipe, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(pipe), 1 << Direction.EAST.get3DDataValue());
        data(helper, pipe).setUpgradeLevel(Direction.EAST, 2);
        helper.assertTrue(pullsEast(helper, pipe), "Setup: the pipe doesn't pull east");
        setBlockLikeCommand(helper, spot);
        helper.runAfterDelay(2, () -> {
            int upgrade = helper.getLevel().getBlockEntity(helper.absolutePos(pipe)) instanceof PipeDataBlockEntity d
                    ? d.upgradeLevel(Direction.EAST) : 0;
            int dropped = itemsOnGround(helper);
            helper.assertBlockPresent(ModBlocks.ITEM_PIPE.get(), spot);
            helper.assertTrue(!pullsEast(helper, pipe), "The neighbor still pulls from the /setblock pipe");
            helper.assertTrue(upgrade == 0, "The neighbor kept upgrade " + upgrade);
            helper.assertTrue(dropped == 1, dropped + " items dropped (should be the 1 upgrade)");
            helper.succeed();
        });
    }

    /** A pipe put down without a player (a pasted build) keeps a neighbor's Off link toward it. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void commandPlacementKeepsOffLink(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(2, 1, 2);
        BlockPos spot = pipe.east();
        int east = 1 << Direction.EAST.get3DDataValue();
        helper.setBlock(pipe, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(pipe), east); // creates the block entity
        data(helper, pipe).setMasks(0, east);
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(pipe), 0); // recompute arms: east is Off
        setBlockLikeCommand(helper, spot);
        helper.runAfterDelay(2, () -> {
            boolean off = helper.getLevel().getBlockEntity(helper.absolutePos(pipe)) instanceof PipeDataBlockEntity d
                    && d.isDisconnected(Direction.EAST);
            SideState side = helper.getBlockState(pipe).getValue(BasePipeBlock.propertyFor(Direction.EAST));
            helper.assertTrue(off, "The /setblock pipe cleared the neighbor's Off mark");
            helper.assertTrue(side != SideState.CONNECTED, "The Off side connected to the /setblock pipe");
            helper.assertTrue(itemsOnGround(helper) == 0, "Something dropped");
            helper.succeed();
        });
    }

    /** What /setblock does: set with flag 2 (clients only), then a neighbor update. */
    private static void setBlockLikeCommand(GameTestHelper helper, BlockPos relative) {
        BlockPos at = helper.absolutePos(relative);
        helper.getLevel().setBlock(at, ModBlocks.ITEM_PIPE.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.getLevel().blockUpdated(at, ModBlocks.ITEM_PIPE.get());
    }

    @Nullable
    private static BlockPos cancelPlaceAt;
    private static boolean listening;

    private static void onPlaceEvent(BlockEvent.EntityPlaceEvent event) {
        if (cancelPlaceAt != null && event.getPos().equals(cancelPlaceAt)) {
            event.setCanceled(true);
        }
    }

    private static void placeNextToPullSide(GameTestHelper helper, boolean cancel) {
        if (!listening) {
            listening = true;
            MinecraftForge.EVENT_BUS.addListener(ReviewLeftoverTests::onPlaceEvent);
        }
        BlockPos pipe = new BlockPos(2, 1, 2);
        BlockPos spot = pipe.east();
        helper.setBlock(spot.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(pipe, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(pipe), 1 << Direction.EAST.get3DDataValue());
        data(helper, pipe).setUpgradeLevel(Direction.EAST, 2);
        helper.assertTrue(pullsEast(helper, pipe), "Setup: the pipe doesn't pull east");
        Player player = helper.makeMockPlayer();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModBlocks.ITEM_PIPE.get()));
        BlockPos floor = helper.absolutePos(spot.below());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false);
        cancelPlaceAt = cancel ? helper.absolutePos(spot) : null;
        InteractionResult result;
        try {
            result = player.getItemInHand(InteractionHand.MAIN_HAND)
                    .useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        } finally {
            cancelPlaceAt = null;
        }
        InteractionResult placed = result;
        helper.runAfterDelay(2, () -> {
            boolean pulls = pullsEast(helper, pipe);
            // With no pull side and no upgrade left, the pipe drops its (empty) settings storage: that reads as 0.
            int upgrade = helper.getLevel().getBlockEntity(helper.absolutePos(pipe)) instanceof PipeDataBlockEntity d
                    ? d.upgradeLevel(Direction.EAST) : 0;
            int dropped = itemsOnGround(helper);
            if (cancel) {
                helper.assertBlockNotPresent(ModBlocks.ITEM_PIPE.get(), spot);
                helper.assertTrue(pulls, "Cancelled placement took the neighbor's pull side (result " + placed + ")");
                helper.assertTrue(upgrade == 2, "Cancelled placement: neighbor's upgrade is " + upgrade + " (should be 2)");
                helper.assertTrue(dropped == 0, "Cancelled placement dropped " + dropped + " item(s)");
            } else {
                helper.assertBlockPresent(ModBlocks.ITEM_PIPE.get(), spot);
                helper.assertTrue(!pulls, "The neighbor still pulls from the new pipe");
                helper.assertTrue(upgrade == 0, "The neighbor kept upgrade " + upgrade);
                helper.assertTrue(dropped == 1, dropped + " items dropped (should be the 1 upgrade)");
            }
            helper.succeed();
        });
    }

    private static boolean pullsEast(GameTestHelper helper, BlockPos pipe) {
        return helper.getBlockState(pipe).getValue(BasePipeBlock.propertyFor(Direction.EAST)) == SideState.EXTRACT;
    }

    static int itemsOnGround(GameTestHelper helper) {
        AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(9, 4, 5))));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).size();
    }

    // ---------------------------------------------------------------- step 119 (review R10)

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 7 item pipes at z 2 with a barrel north, south and above each: 21 destinations. Returns the first pipe. */
    private static BlockPos barrelNetwork(GameTestHelper helper) {
        for (int x = 1; x <= 7; x++) {
            BlockPos pipe = new BlockPos(x, 1, 2);
            helper.setBlock(pipe, ModBlocks.ITEM_PIPE.get().defaultBlockState());
            helper.setBlock(pipe.north(), Blocks.BARREL.defaultBlockState());
            helper.setBlock(pipe.south(), Blocks.BARREL.defaultBlockState());
            helper.setBlock(pipe.above(), Blocks.BARREL.defaultBlockState());
        }
        for (int x = 1; x <= 7; x++) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 2)));
        }
        return new BlockPos(1, 1, 2);
    }

    /** A rebuilt network still knows its destinations' inventories: the same handler objects, no new lookups. */
    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void destinationCachesSurviveRebuild(GameTestHelper helper) {
        BlockPos first = barrelNetwork(helper);
        helper.runAfterDelay(2, () -> {
            NetworkManager manager = NetworkManager.get(helper.getLevel());
            PipeNetwork network = network(helper, first);
            helper.assertTrue(network.endpoints().size() == 21, "Expected 21 destinations, found " + network.endpoints().size());
            IItemHandler[] before = new IItemHandler[21];
            for (int i = 0; i < 21; i++) {
                before[i] = network.endpoints().get(i).itemHandler(helper.getLevel());
                helper.assertTrue(before[i] != null, "Destination " + i + " has no inventory");
            }
            manager.onPipeChanged(helper.absolutePos(first));
            PipeNetwork rebuilt = network(helper, first);
            helper.assertTrue(rebuilt != network, "The network wasn't rebuilt");
            int reused = 0;
            for (int i = 0; i < 21; i++) {
                PipeNetwork.Endpoint endpoint = rebuilt.endpoints().get(i);
                IItemHandler now = endpoint.itemHandler(helper.getLevel());
                for (IItemHandler old : before) {
                    if (old == now) {
                        reused++;
                        break;
                    }
                }
            }
            helper.assertTrue(reused == 21, "Only " + reused + " of 21 destinations kept their cached inventory");
            helper.succeed();
        });
    }

    /** Caches no network uses any more are let go after a few seconds (memory stays bounded). */
    @GameTest(template = EMPTY, timeoutTicks = 320)
    public static void unusedDestinationCachesAreDropped(GameTestHelper helper) {
        BlockPos first = barrelNetwork(helper);
        BlockPos barrel = helper.absolutePos(first.north());
        NetworkManager manager = NetworkManager.get(helper.getLevel());
        helper.runAfterDelay(2, () -> {
            PipeNetwork network = network(helper, first);
            for (int i = 0; i < network.endpoints().size(); i++) {
                network.endpoints().get(i).itemHandler(helper.getLevel());
            }
            helper.assertTrue(manager.holdsTargetCaps(barrel, Direction.SOUTH), "No cache held while in use");
            for (int x = 1; x <= 7; x++) {
                helper.setBlock(new BlockPos(x, 1, 2), Blocks.AIR.defaultBlockState());
            }
        });
        helper.runAfterDelay(6, () -> helper.assertTrue(manager.holdsTargetCaps(barrel, Direction.SOUTH),
                "Cache dropped at once (a rebuild right after a change couldn't reuse it)"));
        helper.succeedWhen(() -> helper.assertTrue(!manager.holdsTargetCaps(barrel, Direction.SOUTH),
                "Cache still held after the pipes were removed"));
    }

    /**
     * Not a pass/fail test: rebuilds the 21-destination network 2,000 times and logs the average time per rebuild,
     * with and without asking every destination for its inventory afterwards (what the first transfers after a
     * rebuild do). Own batch, so nothing else runs beside it. Look for "R10 bench" in the log.
     */
    @GameTest(template = EMPTY, batch = "bench", timeoutTicks = 200)
    public static void rebuildBench(GameTestHelper helper) {
        BlockPos first = barrelNetwork(helper);
        helper.runAfterDelay(2, () -> {
            NetworkManager manager = NetworkManager.get(helper.getLevel());
            BlockPos at = helper.absolutePos(first);
            int rounds = 2000;
            long[] best = {Long.MAX_VALUE, Long.MAX_VALUE};
            for (int pass = 0; pass < 6; pass++) { // first passes warm up; best of the rest
                for (int withCaps = 0; withCaps < 2; withCaps++) {
                    long start = System.nanoTime();
                    for (int r = 0; r < rounds; r++) {
                        manager.onPipeChanged(at);
                        PipeNetwork network = manager.getOrBuild(at);
                        if (withCaps == 1) {
                            for (int i = 0; i < network.endpoints().size(); i++) {
                                network.endpoints().get(i).itemHandler(helper.getLevel());
                            }
                        }
                    }
                    long perRound = (System.nanoTime() - start) / rounds;
                    if (pass >= 2) {
                        best[withCaps] = Math.min(best[withCaps], perRound);
                    }
                }
            }
            LOGGER.info("R10 bench (DEV_STEP {}): rebuild of 7 pipes / 21 destinations: {} us; rebuild + first inventory "
                    + "lookups: {} us", Daisychain.DEV_STEP, String.format("%.2f", best[0] / 1000.0),
                    String.format("%.2f", best[1] / 1000.0));
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- step 120 (dev code out of the jar)

    /** The dev-only commands (src/dev) join the mod's own /daisychain command without replacing any of it. */
    @GameTest(template = EMPTY, timeoutTicks = 20)
    public static void devCommandsMergeIntoDaisychain(GameTestHelper helper) {
        var root = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("daisychain");
        helper.assertTrue(root != null, "No /daisychain command");
        for (String child : new String[]{"network", "stats", "lag", "undo", "redo", "stress", "testbench"}) {
            helper.assertTrue(root.getChild(child) != null, "/daisychain " + child + " is missing");
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- step 123 (Send every)

    /**
     * Chest A (640 cobblestone) -> one pipe -> chest B. Diamond upgrade (64 per tick) with Send every = 40 ticks:
     * after 100 ticks only a few sends of 64 may have happened (without the setting all 640 move in 10 ticks).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void sendEverySlowsSide(GameTestHelper helper) {
        BlockPos b = A.east(2);
        ItemStack[] stacks = new ItemStack[10];
        for (int i = 0; i < stacks.length; i++) {
            stacks[i] = new ItemStack(Items.COBBLESTONE, 64);
        }
        chest(helper, A, stacks);
        chest(helper, b);
        helper.setBlock(A.east(), ModBlocks.ITEM_PIPE.get().defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(A.east()));
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(A.east()), 1 << Direction.WEST.get3DDataValue());
        PipeDataBlockEntity data = data(helper, A.east());
        data.setUpgradeLevel(Direction.WEST, 3); // diamond
        data.applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_INTERVAL, 40, PipeKind.ITEMS);
        helper.assertTrue(data.sendInterval(Direction.WEST, PipeKind.ITEMS) == 40, "Send every wasn't stored");
        helper.runAfterDelay(100, () -> {
            int moved = count(helper, b);
            helper.assertTrue(moved >= 64 && moved <= 64 * 4, moved + " items moved in 100 ticks (expected 64 to 256)");
            helper.assertTrue(moved + count(helper, A) == 640, "Items went missing");
            helper.succeed();
        });
    }

    /** Send every faster than the upgrade allows changes nothing: iron (16 every 10 ticks) set to 2 ticks. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void sendEveryNeverBeatsUpgrade(GameTestHelper helper) {
        BlockPos b = A.east(2);
        chest(helper, A, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64));
        chest(helper, b);
        helper.setBlock(A.east(), ModBlocks.ITEM_PIPE.get().defaultBlockState());
        BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(A.east()));
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(A.east()), 1 << Direction.WEST.get3DDataValue());
        PipeDataBlockEntity data = data(helper, A.east());
        data.setUpgradeLevel(Direction.WEST, 1); // iron
        data.applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_INTERVAL, 2, PipeKind.ITEMS);
        helper.runAfterDelay(50, () -> {
            int moved = count(helper, b);
            helper.assertTrue(moved <= 16 * 6, moved + " items moved in 50 ticks: faster than the iron upgrade");
            helper.succeed();
        });
    }

    /** The Settings Copier carries Send every, and a bad value in a copied tag becomes 0 (follow the upgrade). */
    @GameTest(template = EMPTY, timeoutTicks = 20)
    public static void sendEveryCopiesAndValidates(GameTestHelper helper) {
        BlockPos one = new BlockPos(2, 1, 2);
        BlockPos two = new BlockPos(5, 1, 2);
        helper.setBlock(one, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        helper.setBlock(two, ModBlocks.ITEM_PIPE.get().defaultBlockState());
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(one), 1 << Direction.WEST.get3DDataValue());
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(two), 1 << Direction.WEST.get3DDataValue());
        var registries = helper.getLevel().registryAccess();
        data(helper, one).applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_INTERVAL, 1234, PipeKind.ITEMS);
        var tag = data(helper, one).copySide(Direction.WEST, false, registries);
        data(helper, two).pasteSide(Direction.WEST, false, tag, registries);
        helper.assertTrue(data(helper, two).sendInterval(Direction.WEST, PipeKind.ITEMS) == 1234, "Not pasted");
        tag.getCompound("Items").putInt("Interval", -5);
        data(helper, two).pasteSide(Direction.WEST, false, tag, registries);
        helper.assertTrue(data(helper, two).sendInterval(Direction.WEST, PipeKind.ITEMS) == 0, "Bad value kept");
        data(helper, two).applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_INTERVAL,
                PipeDataBlockEntity.MAX_SEND_INTERVAL + 1, PipeKind.ITEMS);
        helper.assertTrue(data(helper, two).sendInterval(Direction.WEST, PipeKind.ITEMS) == 0, "Too big accepted");
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    static PipeNetwork network(GameTestHelper helper, BlockPos pipe) {
        return NetworkManager.get(helper.getLevel()).getOrBuild(helper.absolutePos(pipe));
    }

    static PipeDataBlockEntity data(GameTestHelper helper, BlockPos pipe) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pipe)) instanceof PipeDataBlockEntity data) {
            return data;
        }
        throw new IllegalStateException("No pipe settings at " + pipe);
    }

    static void chest(GameTestHelper helper, BlockPos pos, ItemStack... contents) {
        helper.setBlock(pos, Blocks.CHEST.defaultBlockState());
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < contents.length; i++) {
                chest.setItem(i, contents[i]);
            }
        }
    }

    static int count(GameTestHelper helper, BlockPos pos) {
        int total = 0;
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < chest.getContainerSize(); i++) {
                total += chest.getItem(i).getCount();
            }
        }
        return total;
    }
}
