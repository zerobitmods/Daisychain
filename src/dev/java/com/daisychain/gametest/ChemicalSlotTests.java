package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Step 170: per-side settings have a fourth resource slot (chemicals). Pipes saved with three resources (1.5.1 and
 * older: 18 values per array) must load with every setting where it was.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalSlotTests {
    private static final String EMPTY = "empty";
    private static final BlockPos PIPE = new BlockPos(1, 1, 1);
    private static final int OLD_SLOTS = 3 * 6;

    private ChemicalSlotTests() {
    }

    /** A distinct value per (resource, side), valid for every resource's limits. */
    private static int value(int key, int slot) {
        return 100 + key * 20 + slot;
    }

    /** Bit patterns that differ per setting, inside the old 18 bits. */
    private static final int PULL_OFF = 0b100000_010000_001000;
    private static final int INSERT_OFF = 0b000100_000010_000001;
    private static final int FILTER_DENY = 0b001000_000100_000010;
    private static final int INSERT_DENY = 0b010000_001000_000100;

    @GameTest(template = EMPTY)
    public static void oldThreeResourceSaveLoads(GameTestHelper helper) {
        helper.setBlock(PIPE, ModBlocks.UNIVERSAL_PIPE.get().defaultBlockState().setValue(BasePipeBlock.HAS_DATA, true));
        BlockPos at = helper.absolutePos(PIPE);
        var registries = helper.getLevel().registryAccess();
        PipeDataBlockEntity data = BasePipeBlock.getData(helper.getLevel(), at, helper.getLevel().getBlockState(at));
        helper.assertTrue(data != null, "Setup: no settings storage");
        // A filter entry, saved the way every version saves filters (with its resource), to check it stays put.
        data.setFilterEntry(Direction.NORTH, 0, new ItemStack(Items.STONE), false, PipeKind.ITEMS);
        CompoundTag old = data.saveWithoutMetadata(registries);
        // The 1.5.1 layout: 18 values per array, bits for three resources.
        String[] keys = {"Batch", "Keep", "Fill", "MaxStock", "Interval"};
        for (int k = 0; k < keys.length; k++) {
            int[] values = new int[OLD_SLOTS];
            for (int i = 0; i < OLD_SLOTS; i++) {
                values[i] = value(k, i);
            }
            old.putIntArray(keys[k], values);
        }
        old.remove("ChemicalsKnown"); // step 176: 1.5.1 didn't write it (it marks saves made since)
        old.putInt("PullOff", PULL_OFF);
        old.putInt("InsertOff", INSERT_OFF);
        old.putInt("FilterDeny", FILTER_DENY);
        old.putInt("InsertDeny", INSERT_DENY);

        PipeDataBlockEntity loaded = new PipeDataBlockEntity(at, helper.getBlockState(PIPE));
        loaded.loadWithComponents(old, registries);
        check(helper, loaded, "old save");
        // Saved again in the new layout and read back: still the same, and the arrays have the chemicals slot.
        CompoundTag saved = loaded.saveWithoutMetadata(registries);
        helper.assertTrue(saved.getIntArray("Batch").length == PipeKind.RESOURCE_COUNT * 6,
                "Saved Batch has " + saved.getIntArray("Batch").length + " values");
        PipeDataBlockEntity again = new PipeDataBlockEntity(at, helper.getBlockState(PIPE));
        again.loadWithComponents(saved, registries);
        check(helper, again, "new save");
        // Clients get the same values in block updates.
        PipeDataBlockEntity client = new PipeDataBlockEntity(at, helper.getBlockState(PIPE));
        client.loadWithComponents(loaded.getUpdateTag(registries), registries);
        checkNumbers(helper, client, "client update");
        // The Settings Copier copies all four resources of a universal pipe (step 176).
        CompoundTag copy = loaded.copySide(Direction.NORTH, false, registries);
        helper.assertTrue(copy.contains("Items") && copy.contains("Fluids") && copy.contains("Energy")
                && copy.contains("Chemicals"), "Copier lost a resource: " + copy.getAllKeys());
        helper.succeed();
    }

    /**
     * Step 176: a universal pipe placed now pulls chemicals on its sides by default, and keeps that through a save; the
     * old-save rule (chemicals start off) only applies to pipes saved before.
     */
    @GameTest(template = EMPTY)
    public static void newUniversalPullsChemicals(GameTestHelper helper) {
        helper.setBlock(PIPE, ModBlocks.UNIVERSAL_PIPE.get().defaultBlockState().setValue(BasePipeBlock.HAS_DATA, true));
        BlockPos at = helper.absolutePos(PIPE);
        var registries = helper.getLevel().registryAccess();
        PipeDataBlockEntity data = BasePipeBlock.getData(helper.getLevel(), at, helper.getLevel().getBlockState(at));
        helper.assertTrue(data != null, "Setup: no settings storage");
        PipeDataBlockEntity again = new PipeDataBlockEntity(at, helper.getBlockState(PIPE));
        again.loadWithComponents(data.saveWithoutMetadata(registries), registries);
        for (Direction side : Direction.values()) {
            helper.assertTrue(data.pullsResource(side, PipeKind.CHEMICALS), "New pipe: chemicals off on " + side);
            helper.assertTrue(again.pullsResource(side, PipeKind.CHEMICALS), "Saved and loaded: chemicals off on " + side);
        }
        helper.succeed();
    }

    private static void check(GameTestHelper helper, PipeDataBlockEntity data, String what) {
        checkNumbers(helper, data, what);
        helper.assertTrue(data.activeFilter(Direction.NORTH, PipeKind.ITEMS) != null, what + ": the item filter is gone");
    }

    private static void checkNumbers(GameTestHelper helper, PipeDataBlockEntity data, String what) {
        helper.assertTrue(data.carries(PipeKind.CHEMICALS), what + ": universal pipe doesn't carry chemicals");
        for (int resource = 0; resource < 3; resource++) {
            for (Direction side : Direction.values()) {
                int i = resource * 6 + side.get3DDataValue();
                String where = what + ", resource " + resource + ", " + side + ": ";
                expect(helper, data.batch(side, resource), value(0, i), where + "Batch");
                expect(helper, data.keepInSource(side, resource), value(1, i), where + "Keep");
                expect(helper, data.fillDestinationTo(side, resource), value(2, i), where + "Fill");
                expect(helper, data.maxStock(side, resource), value(3, i), where + "Max stock");
                expect(helper, data.sendInterval(side, resource), value(4, i), where + "Send every");
                helper.assertTrue(data.pullsResource(side, resource) == ((PULL_OFF >> i & 1) == 0), where + "pull off");
                helper.assertTrue(data.acceptsResource(side, resource) == ((INSERT_OFF >> i & 1) == 0),
                        where + "insert off");
                helper.assertTrue(data.filterDenies(side, resource) == ((FILTER_DENY >> i & 1) != 0), where + "deny");
                helper.assertTrue(data.insertFilterDenies(side, resource) == ((INSERT_DENY >> i & 1) != 0),
                        where + "insert deny");
            }
        }
        for (Direction side : Direction.values()) {
            expect(helper, data.batch(side, PipeKind.CHEMICALS), 0, what + ", chemicals " + side + ": Batch");
            // Step 176: saved before universal pipes carried chemicals, so they start switched off on every side.
            helper.assertFalse(data.pullsResource(side, PipeKind.CHEMICALS), what + ": chemicals on at " + side);
        }
    }

    private static void expect(GameTestHelper helper, int actual, int expected, String what) {
        helper.assertTrue(actual == expected, what + " is " + actual + " (should be " + expected + ")");
    }
}
