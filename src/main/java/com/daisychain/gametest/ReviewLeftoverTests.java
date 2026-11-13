package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.network.NetworkManager;
import com.daisychain.network.PipeNetwork;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

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
            FlowPipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(A.east(i)));
        }
        FlowPipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(A.east()), 1 << Direction.WEST.get3DDataValue());
        data(helper, A.east()).setUpgradeLevel(Direction.WEST, 1); // iron: 16 every 10 ticks
        FlowPipeBlock.ensureData(helper.getLevel(), helper.absolutePos(last)); // B's insert side gets settings storage
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
