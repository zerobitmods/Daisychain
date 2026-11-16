package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.Distribution;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Step 139: within one item transfer, destination slots already filled with another item (or seen full) aren't asked
 * again. Big transfers (Nether Star) into chests used to ask the first slot once per stack, the second once per stack
 * but one, and so on. Items must still land in exactly the same slots as before, and still find any room there is.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SlotMemoryTests {
    private static final String EMPTY = "empty";
    private static final BlockPos A = new BlockPos(1, 1, 2);
    private static final BlockPos B = new BlockPos(5, 1, 2);
    /** Second destination for round robin: north of the middle pipe. */
    private static final BlockPos C = new BlockPos(3, 1, 1);
    private static final Item[] FILL = {Items.COBBLESTONE, Items.DIRT, Items.SAND};
    private static final int NETHER_STAR = 5;

    private SlotMemoryTests() {
    }

    /**
     * The stress test layout in one network: 13 stacks (cobblestone, dirt, sand in turn) moved by one Nether Star
     * transfer into an empty 27-slot inventory. Before step 139 the destination got 104 calls (stack k asked slots
     * 0..k-1 again, then 1 real insert); now 36 (each stack: at most its own item's last slot, the free slot, the
     * real insert). Same slots as before: stack k in slot k.
     */
    @GameTest(template = EMPTY, timeoutTicks = 60)
    public static void bigTransferSkipsFilledSlots(GameTestHelper helper) {
        TestInventories.Counting source = new TestInventories.Counting(27);
        for (int i = 0; i < 13; i++) {
            source.setStackInSlot(i, new ItemStack(FILL[i % 3], 64));
        }
        TestInventories.Counting destination = new TestInventories.Counting(27);
        table(helper, A, source);
        table(helper, B, destination);
        line(helper, A.east(), 3);
        pull(helper, A.east(), NETHER_STAR);
        helper.runAfterDelay(30, () -> {
            close(helper, A, B);
            helper.assertTrue(itemsOnGround(helper) == 0, "Items were dropped at the pipe");
            helper.assertTrue(source.total() == 0, "Source still holds " + source.total());
            helper.assertTrue(destination.total() == 832, "Destination holds " + destination.total() + " (should be 832)");
            for (int i = 0; i < 13; i++) {
                expect(helper, destination, i, FILL[i % 3], 64);
            }
            helper.assertTrue(destination.calls <= 36,
                    "Destination got " + destination.calls + " calls for 13 stacks (should be 36 at most)");
            helper.succeed();
        });
    }

    /**
     * Round robin splits every stack in two, so each destination gets half stacks. The second half of an item must top
     * up the first (vanilla packing: 6 full stacks and a half in each), not start a new slot.
     */
    @GameTest(template = EMPTY, timeoutTicks = 60)
    public static void roundRobinStillPacksStacks(GameTestHelper helper) {
        TestInventories.Counting source = new TestInventories.Counting(27);
        for (int i = 0; i < 13; i++) {
            source.setStackInSlot(i, new ItemStack(FILL[i % 3], 64));
        }
        TestInventories.Counting one = new TestInventories.Counting(27);
        TestInventories.Counting two = new TestInventories.Counting(27);
        table(helper, A, source);
        table(helper, B, one);
        table(helper, C, two);
        line(helper, A.east(), 3);
        pull(helper, A.east(), NETHER_STAR);
        data(helper, A.east()).applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_DISTRIBUTION,
                Distribution.ROUND_ROBIN.ordinal(), PipeKind.ITEMS);
        helper.runAfterDelay(30, () -> {
            close(helper, A, B, C);
            helper.assertTrue(itemsOnGround(helper) == 0, "Items were dropped at the pipe");
            helper.assertTrue(one.total() == 416 && two.total() == 416,
                    "Destinations hold " + one.total() + " and " + two.total() + " (should be 416 each)");
            for (TestInventories.Counting destination : new TestInventories.Counting[]{one, two}) {
                for (int i = 0; i < 6; i++) {
                    expect(helper, destination, i, FILL[i % 3], 64);
                }
                expect(helper, destination, 6, Items.COBBLESTONE, 32);
            }
            helper.succeed();
        });
    }

    /** What one transfer learned is forgotten by the next: a slot emptied in between is filled first, like a hopper. */
    @GameTest(template = EMPTY, timeoutTicks = 120)
    public static void nextTransferStartsAtFirstSlot(GameTestHelper helper) {
        TestInventories.Counting source = new TestInventories.Counting(27);
        source.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 64));
        source.setStackInSlot(1, new ItemStack(Items.DIRT, 64));
        TestInventories.Counting destination = new TestInventories.Counting(27);
        table(helper, A, source);
        table(helper, B, destination);
        line(helper, A.east(), 3);
        pull(helper, A.east(), NETHER_STAR);
        helper.runAfterDelay(20, () -> {
            expect(helper, destination, 0, Items.COBBLESTONE, 64);
            expect(helper, destination, 1, Items.DIRT, 64);
            destination.setStackInSlot(0, ItemStack.EMPTY); // a player takes the cobblestone out
            source.setStackInSlot(0, new ItemStack(Items.GRAVEL, 64));
        });
        helper.runAfterDelay(60, () -> {
            close(helper, A, B);
            expect(helper, destination, 0, Items.GRAVEL, 64);
            expect(helper, destination, 1, Items.DIRT, 64);
            helper.assertTrue(destination.total() == 128, "Destination holds " + destination.total());
            helper.succeed();
        });
    }

    /**
     * A partial stack of an item in slot 0 is topped up (and only then a new slot is used), and what can't fit stays
     * in the source: same result as before step 139.
     */
    @GameTest(template = EMPTY, timeoutTicks = 60)
    public static void partialStackIsToppedUp(GameTestHelper helper) {
        TestInventories.Counting source = new TestInventories.Counting(27);
        source.setStackInSlot(0, new ItemStack(Items.DIRT, 64));
        source.setStackInSlot(1, new ItemStack(Items.COBBLESTONE, 64));
        source.setStackInSlot(2, new ItemStack(Items.COBBLESTONE, 64));
        TestInventories.Counting destination = new TestInventories.Counting(3);
        destination.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 10));
        table(helper, A, source);
        table(helper, B, destination);
        line(helper, A.east(), 3);
        pull(helper, A.east(), NETHER_STAR);
        helper.runAfterDelay(30, () -> {
            close(helper, A, B);
            helper.assertTrue(itemsOnGround(helper) == 0, "Items were dropped at the pipe");
            expect(helper, destination, 0, Items.COBBLESTONE, 64);
            expect(helper, destination, 1, Items.DIRT, 64);
            expect(helper, destination, 2, Items.COBBLESTONE, 64);
            helper.assertTrue(source.total() == 10, "Source holds " + source.total() + " (should be 10 cobblestone)");
            helper.succeed();
        });
    }

    /**
     * A slot that took one item may still take another (a void slot, a machine that pulls its input straight in): the
     * slot skipped for the second item is asked after all when nothing else has room. Nothing is refused that fits, and
     * not one transfer later either: both items must go in during the same tick (the same transfer). Port review:
     * without the same-tick check this passed with the second round removed (the next transfer delivered the dirt).
     */
    @GameTest(template = EMPTY, timeoutTicks = 60)
    public static void skippedSlotsStillAskedWhenNeeded(GameTestHelper helper) {
        TestInventories.Counting source = new TestInventories.Counting(27);
        source.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 64));
        source.setStackInSlot(1, new ItemStack(Items.DIRT, 64));
        VoidSlot destination = new VoidSlot(helper);
        table(helper, A, source);
        table(helper, B, destination);
        line(helper, A.east(), 3);
        pull(helper, A.east(), NETHER_STAR);
        helper.runAfterDelay(30, () -> {
            close(helper, A, B);
            helper.assertTrue(source.total() == 0, "Source still holds " + source.total() + " (the dirt was refused)");
            helper.assertTrue(destination.taken == 128, "Void slot took " + destination.taken + " (should be 128)");
            helper.assertTrue(destination.firstTick == destination.lastTick, "The dirt went in "
                    + (destination.lastTick - destination.firstTick) + " tick(s) after the cobblestone (should be the "
                    + "same transfer)");
            helper.succeed();
        });
    }

    /** One slot that takes anything and keeps nothing. */
    private static final class VoidSlot extends ItemStackHandler {
        private final GameTestHelper helper;
        int taken;
        long firstTick = -1;
        long lastTick = -1;

        VoidSlot(GameTestHelper helper) {
            super(1);
            this.helper = helper;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (!simulate && !stack.isEmpty()) {
                taken += stack.getCount();
                lastTick = helper.getLevel().getGameTime();
                if (firstTick < 0) {
                    firstTick = lastTick;
                }
            }
            return ItemStack.EMPTY;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void expect(GameTestHelper helper, TestInventories.Counting inventory, int slot, Item item, int count) {
        ItemStack stack = inventory.peek(slot);
        helper.assertTrue(stack.is(item) && stack.getCount() == count, "Slot " + slot + " holds " + stack.getCount()
                + " " + stack.getItem() + " (should be " + count + " " + item + ")");
    }

    private static void table(GameTestHelper helper, BlockPos pos, IItemHandler inventory) {
        SwitchableInventory.setHandler(helper.absolutePos(pos), inventory);
        helper.setBlock(pos, Blocks.ENCHANTING_TABLE.defaultBlockState());
    }

    private static void close(GameTestHelper helper, BlockPos... tables) {
        for (BlockPos pos : tables) {
            SwitchableInventory.set(helper.absolutePos(pos), false);
        }
    }

    private static void line(GameTestHelper helper, BlockPos first, int length) {
        for (int i = 0; i < length; i++) {
            helper.setBlock(first.east(i), ModBlocks.ITEM_PIPE.get().defaultBlockState());
        }
        for (int i = 0; i < length; i++) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(first.east(i)));
        }
    }

    private static void pull(GameTestHelper helper, BlockPos pipe, int upgrade) {
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(pipe),
                1 << Direction.WEST.get3DDataValue());
        data(helper, pipe).setUpgradeLevel(Direction.WEST, upgrade);
    }

    private static PipeDataBlockEntity data(GameTestHelper helper, BlockPos pipe) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(pipe)) instanceof PipeDataBlockEntity data) {
            return data;
        }
        throw new IllegalStateException("No pipe settings at " + pipe);
    }

    private static int itemsOnGround(GameTestHelper helper) {
        AABB area = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
                Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(9, 4, 5))));
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).size();
    }
}
