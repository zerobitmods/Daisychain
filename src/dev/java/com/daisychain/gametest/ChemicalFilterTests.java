package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.compat.Chemicals;
import com.daisychain.filter.ChemicalGhost;
import com.daisychain.filter.FilterMatch;
import com.daisychain.filter.FluidGhost;
import com.daisychain.filter.ItemFilter;
import com.daisychain.filter.MatchMode;
import com.daisychain.item.CopiedSettings;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Step 178: chemical filters. Entries are daisychain:chemical ghosts holding only the chemical's name. The tests that
 * move chemicals need Mekanism (they pass at once without it); the ones about storing entries run either way, since a
 * world must keep its chemical filters when opened without Mekanism.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalFilterTests {
    private static final String EMPTY = "empty";
    private static final ResourceLocation HYDROGEN = ResourceLocation.fromNamespaceAndPath("mekanism", "hydrogen");
    private static final ResourceLocation OXYGEN = ResourceLocation.fromNamespaceAndPath("mekanism", "oxygen");
    private static final BlockPos PULL = new BlockPos(2, 1, 2);

    private ChemicalFilterTests() {
    }

    private static PipeDataBlockEntity data(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        BasePipeBlock.ensureData(helper.getLevel(), at);
        PipeDataBlockEntity data = BasePipeBlock.getData(helper.getLevel(), at, helper.getLevel().getBlockState(at));
        helper.assertTrue(data != null, "Setup: no pipe settings at " + pos);
        return data;
    }

    // ---------------------------------------------------------------- moving (Mekanism only)

    /**
     * Allow filter on the pull side listing oxygen: tank A's hydrogen stays put. Then hydrogen is listed too and all of
     * it moves.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void gasPullFilterAllowsOnlyListed(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        GasPipeTests.gasLine(helper, 8_000, null);
        PipeDataBlockEntity pull = data(helper, PULL);
        pull.setFilterEntry(Direction.WEST, 0, ChemicalGhost.of(OXYGEN), false, PipeKind.CHEMICALS);
        helper.assertTrue(ChemicalGhost.isGhost(pull.filterEntry(Direction.WEST, 0, false, PipeKind.CHEMICALS)),
                "Setup: the gas side's filter refused a chemical entry");
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 0,
                    "Hydrogen got past an Allow filter listing only oxygen: B has " + GasPipeTests.gas(helper, GasPipeTests.TANK_B));
            pull.setFilterEntry(Direction.WEST, 1, ChemicalGhost.of(HYDROGEN), false, PipeKind.CHEMICALS);
            helper.succeedWhen(() -> helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 8_000,
                    "Hydrogen listed, B has " + GasPipeTests.gas(helper, GasPipeTests.TANK_B)));
        });
    }

    /** The universal pipe's Chemicals tab has the same filter: Allow oxygen stops hydrogen there too. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void universalChemicalFilter(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        GasPipeTests.gasLine(helper, 8_000, null, ModBlocks.UNIVERSAL_PIPE.get());
        PipeDataBlockEntity pull = data(helper, PULL);
        pull.setFilterEntry(Direction.WEST, 0, ChemicalGhost.of(OXYGEN), false, PipeKind.CHEMICALS);
        helper.assertTrue(ChemicalGhost.isGhost(pull.filterEntry(Direction.WEST, 0, false, PipeKind.CHEMICALS)),
                "Setup: the universal Chemicals filter refused a chemical entry");
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 0,
                    "Hydrogen got past the universal pipe's Allow-oxygen filter: B has "
                            + GasPipeTests.gas(helper, GasPipeTests.TANK_B));
            helper.succeed();
        });
    }

    /**
     * A Mod entry during a transfer: an oxygen entry stops hydrogen while it matches only oxygen; switched to Mod
     * ("anything from mekanism") it lets hydrogen through.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void chemicalModEntryMatches(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        GasPipeTests.gasLine(helper, 8_000, null);
        PipeDataBlockEntity pull = data(helper, PULL);
        pull.setFilterEntry(Direction.WEST, 0, ChemicalGhost.of(OXYGEN), false, PipeKind.CHEMICALS);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 0,
                    "Hydrogen got past an oxygen-only entry: B has " + GasPipeTests.gas(helper, GasPipeTests.TANK_B));
            pull.setFilterMatch(Direction.WEST, 0, MatchMode.MOD, null, false, PipeKind.CHEMICALS);
            helper.succeedWhen(() -> helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 8_000,
                    "Mod entry (mekanism), B has " + GasPipeTests.gas(helper, GasPipeTests.TANK_B)));
        });
    }

    /** An insert filter entry with its own Max stock of 2000 mB: tank B stops at 2000, A keeps the rest. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void gasInsertEntryMaxStock(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        GasPipeTests.gasLine(helper, 8_000, null);
        PipeDataBlockEntity last = data(helper, new BlockPos(4, 1, 2));
        last.setFilterEntry(Direction.EAST, 0, ChemicalGhost.of(HYDROGEN), true, PipeKind.CHEMICALS);
        last.setFilterNumber(Direction.EAST, 0, ItemFilter.FILL, 2_000, true, PipeKind.CHEMICALS);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 2_000,
                    "B has " + GasPipeTests.gas(helper, GasPipeTests.TANK_B) + " (entry Max stock 2000)");
            helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_A) == 6_000,
                    "A has " + GasPipeTests.gas(helper, GasPipeTests.TANK_A));
            helper.succeed();
        });
    }

    /**
     * A chemical tag entry during a transfer: a carbon entry stops hydrogen; the same entry switched to Mekanism's tag
     * #mekanism:gaseous (which lists hydrogen) lets it through.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void chemicalTagEntryMatches(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        GasPipeTests.gasLine(helper, 8_000, null);
        PipeDataBlockEntity pull = data(helper, PULL);
        pull.setFilterEntry(Direction.WEST, 0, ChemicalGhost.of(ResourceLocation.fromNamespaceAndPath("mekanism", "carbon")), false, PipeKind.CHEMICALS);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 0,
                    "Hydrogen got past a carbon-only entry: B has " + GasPipeTests.gas(helper, GasPipeTests.TANK_B));
            pull.setFilterMatch(Direction.WEST, 0, MatchMode.TAG, ResourceLocation.fromNamespaceAndPath("mekanism", "gaseous"), false,
                    PipeKind.CHEMICALS);
            helper.succeedWhen(() -> helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 8_000,
                    "Tag #mekanism:gaseous, B has " + GasPipeTests.gas(helper, GasPipeTests.TANK_B)));
        });
    }

    /** Deny filter listing hydrogen: nothing moves. */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void gasPullFilterDenies(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        GasPipeTests.gasLine(helper, 8_000, null);
        PipeDataBlockEntity pull = data(helper, PULL);
        pull.applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_FILTER_DENY, 1, PipeKind.CHEMICALS);
        pull.setFilterEntry(Direction.WEST, 0, ChemicalGhost.of(HYDROGEN), false, PipeKind.CHEMICALS);
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 0,
                    "Hydrogen got past a Deny filter: B has " + GasPipeTests.gas(helper, GasPipeTests.TANK_B));
            helper.succeed();
        });
    }

    /** A hydrogen entry with its own Batch of 100 mB: each transfer moves at most 100 mB (the side's Batch is Max). */
    @GameTest(template = EMPTY, timeoutTicks = 100)
    public static void gasEntryOwnBatch(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        GasPipeTests.gasLine(helper, 8_000, null);
        PipeDataBlockEntity pull = data(helper, PULL);
        pull.setFilterEntry(Direction.WEST, 0, ChemicalGhost.of(HYDROGEN), false, PipeKind.CHEMICALS);
        pull.setFilterNumber(Direction.WEST, 0, ItemFilter.BATCH, 100, false, PipeKind.CHEMICALS);
        helper.runAfterDelay(35, () -> {
            long b = GasPipeTests.gas(helper, GasPipeTests.TANK_B);
            // Without upgrades a transfer every 10 ticks: at most 4 transfers of 100 mB by now.
            helper.assertTrue(b > 0 && b <= 400, "B has " + b + " mB after 35 ticks (entry Batch 100)");
            helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_A) + b == 8_000, "Hydrogen made or lost");
            helper.succeed();
        });
    }

    /**
     * Tank C next to the middle pipe is the nearest destination, but its insert side denies hydrogen: everything goes on
     * to tank B, and C never gets any.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void gasInsertFilterDenies(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        BlockPos tankC = new BlockPos(3, 1, 3);
        helper.setBlock(tankC, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mekanism",
                "basic_chemical_tank")).defaultBlockState());
        GasPipeTests.chemicalSidesOn(helper, tankC);
        GasPipeTests.gasLine(helper, 8_000, null);
        PipeDataBlockEntity middle = data(helper, new BlockPos(3, 1, 2));
        middle.applySetting(Direction.SOUTH, PipeDataBlockEntity.SETTING_INSERT_DENY, 1, PipeKind.CHEMICALS);
        middle.setFilterEntry(Direction.SOUTH, 0, ChemicalGhost.of(HYDROGEN), true, PipeKind.CHEMICALS);
        helper.onEachTick(() -> {
            if (GasPipeTests.gas(helper, tankC) > 0) {
                helper.fail("Tank C took hydrogen through an insert side that denies it");
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(GasPipeTests.gas(helper, GasPipeTests.TANK_B) == 8_000,
                "B has " + GasPipeTests.gas(helper, GasPipeTests.TANK_B)));
    }

    /**
     * Route A: a filled chemical tank item (or jetpack, or scuba tank) lists its chemical, an empty one nothing. Route B: a chemical dragged from
     * JEI (a Mekanism ChemicalStack) gives its name; anything else gives none.
     */
    @GameTest(template = EMPTY)
    public static void chemicalFromTankItemAndJei(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        ItemStack full = ChemicalGhost.listable(MekanismTestKit.tankItem("mekanism:hydrogen", 1_000));
        helper.assertTrue(HYDROGEN.equals(ChemicalGhost.idOf(full)), "Filled tank listed " + full);
        helper.assertTrue(ChemicalGhost.listable(MekanismTestKit.tankItem("mekanism:hydrogen", 0)).isEmpty(),
                "An empty tank listed something");
        ItemStack jetpack = ChemicalGhost.listable(MekanismTestKit.chemicalItem("mekanism:jetpack", "mekanism:hydrogen", 1_000));
        helper.assertTrue(HYDROGEN.equals(ChemicalGhost.idOf(jetpack)), "Filled jetpack listed " + jetpack);
        ItemStack scuba = ChemicalGhost.listable(MekanismTestKit.chemicalItem("mekanism:scuba_tank", "mekanism:oxygen", 1_000));
        helper.assertTrue(OXYGEN.equals(ChemicalGhost.idOf(scuba)), "Filled scuba tank listed " + scuba);
        helper.assertTrue(OXYGEN.equals(Chemicals.idOfIngredient(MekanismTestKit.jeiIngredient("mekanism:oxygen"))),
                "JEI oxygen read as " + Chemicals.idOfIngredient(MekanismTestKit.jeiIngredient("mekanism:oxygen")));
        helper.assertTrue(Chemicals.idOfIngredient(new ItemStack(net.minecraft.world.item.Items.STONE)) == null,
                "Stone read as a chemical");
        helper.assertTrue(ChemicalGhost.listable(ChemicalGhost.of(ResourceLocation.fromNamespaceAndPath("mekanism",
                "no_such_gas"))).isEmpty(), "A chemical that doesn't exist was listed");
        helper.succeed();
    }

    // ---------------------------------------------------------------- storing (with or without Mekanism)

    /** Chemical entries go only in chemical filters; fluid ghosts and items never do. */
    @GameTest(template = EMPTY)
    public static void chemicalEntriesStayInChemicalFilters(GameTestHelper helper) {
        BlockPos gas = new BlockPos(2, 1, 2);
        BlockPos fluid = new BlockPos(4, 1, 2);
        helper.setBlock(gas, ModBlocks.GAS_PIPE.get().defaultBlockState());
        helper.setBlock(fluid, ModBlocks.FLUID_PIPE.get().defaultBlockState());
        PipeDataBlockEntity gasData = data(helper, gas);
        PipeDataBlockEntity fluidData = data(helper, fluid);
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        gasData.setFilterEntry(Direction.WEST, 0, FluidGhost.of(new FluidStack(Fluids.WATER, 1000), registries), false,
                PipeKind.CHEMICALS);
        gasData.setFilterEntry(Direction.WEST, 1, new ItemStack(net.minecraft.world.item.Items.STONE), false,
                PipeKind.CHEMICALS);
        gasData.setFilterEntry(Direction.WEST, 2, ChemicalGhost.of(HYDROGEN), false, PipeKind.CHEMICALS);
        fluidData.setFilterEntry(Direction.WEST, 0, ChemicalGhost.of(HYDROGEN), false, PipeKind.FLUIDS);
        helper.assertTrue(gasData.filterEntry(Direction.WEST, 0, false, PipeKind.CHEMICALS).isEmpty(),
                "A fluid went into a chemical filter");
        helper.assertTrue(gasData.filterEntry(Direction.WEST, 1, false, PipeKind.CHEMICALS).isEmpty(),
                "An item went into a chemical filter");
        helper.assertTrue(ChemicalGhost.isGhost(gasData.filterEntry(Direction.WEST, 2, false, PipeKind.CHEMICALS)),
                "The chemical filter refused a chemical");
        helper.assertTrue(fluidData.filterEntry(Direction.WEST, 0, false, PipeKind.FLUIDS).isEmpty(),
                "A chemical went into a fluid filter");
        helper.succeed();
    }

    /**
     * A chemical filter saves and loads by name, with its match mode, tag and numbers, even without Mekanism (the entry
     * then matches nothing but stays listed).
     */
    @GameTest(template = EMPTY)
    public static void chemicalFilterSavesByName(GameTestHelper helper) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        ItemFilter filter = new ItemFilter();
        filter.set(0, ChemicalGhost.of(HYDROGEN), registries);
        filter.setNumber(0, ItemFilter.KEEP, 500);
        filter.set(1, ChemicalGhost.of(OXYGEN), registries);
        ResourceLocation tag = ResourceLocation.fromNamespaceAndPath("daisychain", "test_gases");
        helper.assertTrue(filter.setMatch(1, MatchMode.TAG, tag), "Setup: tag mode refused");
        ItemFilter loaded = new ItemFilter();
        loaded.load(filter.save(registries), registries);
        ItemStack first = loaded.display(0);
        helper.assertTrue(HYDROGEN.equals(ChemicalGhost.idOf(first)), "Entry 0 loaded as " + first);
        helper.assertTrue(loaded.number(0, ItemFilter.KEEP) == 500, "Keep loaded as " + loaded.number(0, ItemFilter.KEEP));
        ItemStack second = loaded.display(1);
        FilterMatch match = second.get(ModDataComponents.FILTER_MATCH.get());
        helper.assertTrue(OXYGEN.equals(ChemicalGhost.idOf(second)), "Entry 1 loaded as " + second);
        helper.assertTrue(match != null && match.matchMode() == MatchMode.TAG && match.value().equals(tag.toString()),
                "Entry 1 match loaded as " + match);
        helper.assertFalse(ItemFilter.hasData(first), "A chemical entry claims to have data");
        helper.succeed();
    }

    /** A Settings Copier copy of a gas pull side pastes its chemical filter (entry, mode, numbers) onto another. */
    @GameTest(template = EMPTY)
    public static void copierPastesChemicalFilter(GameTestHelper helper) {
        BlockPos one = new BlockPos(2, 1, 2);
        BlockPos two = new BlockPos(2, 1, 4);
        helper.setBlock(one, ModBlocks.GAS_PIPE.get().defaultBlockState());
        helper.setBlock(two, ModBlocks.GAS_PIPE.get().defaultBlockState());
        for (BlockPos pos : new BlockPos[] {one, two}) {
            BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(pos), 1 << Direction.WEST.get3DDataValue());
        }
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        PipeDataBlockEntity from = data(helper, one);
        from.applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_FILTER_DENY, 1, PipeKind.CHEMICALS);
        from.setFilterEntry(Direction.WEST, 3, ChemicalGhost.of(OXYGEN), false, PipeKind.CHEMICALS);
        from.setFilterNumber(Direction.WEST, 3, ItemFilter.BATCH, 250, false, PipeKind.CHEMICALS);
        data(helper, two).pasteSide(Direction.WEST, false, from.copySide(Direction.WEST, false, registries), registries);
        PipeDataBlockEntity to = data(helper, two);
        ItemStack entry = to.filterEntry(Direction.WEST, 3, false, PipeKind.CHEMICALS);
        helper.assertTrue(OXYGEN.equals(ChemicalGhost.idOf(entry)), "Pasted entry 3 is " + entry);
        helper.assertTrue(to.filterDenies(Direction.WEST, PipeKind.CHEMICALS), "Deny mode didn't paste");
        com.daisychain.filter.FilterNumbers numbers = entry.get(ModDataComponents.FILTER_NUMBERS.get());
        helper.assertTrue(numbers != null && numbers.batch() == 250, "Pasted entry numbers " + numbers);
        helper.succeed();
    }

    /** The Settings Copier's tooltip counts chemical filter entries (its RESOURCE_KEYS lacked "Chemicals"). */
    @GameTest(template = EMPTY)
    public static void copierCountsChemicalEntries(GameTestHelper helper) {
        BlockPos gas = new BlockPos(2, 1, 2);
        helper.setBlock(gas, ModBlocks.GAS_PIPE.get().defaultBlockState());
        PipeDataBlockEntity data = data(helper, gas);
        data.setFilterEntry(Direction.WEST, 0, ChemicalGhost.of(HYDROGEN), false, PipeKind.CHEMICALS);
        data.setFilterEntry(Direction.WEST, 1, ChemicalGhost.of(OXYGEN), false, PipeKind.CHEMICALS);
        Block block = helper.getLevel().getBlockState(helper.absolutePos(gas)).getBlock();
        CopiedSettings copied = new CopiedSettings(false, BuiltInRegistries.BLOCK.getKey(block).toString(),
                data.copySide(Direction.WEST, false, helper.getLevel().registryAccess()));
        helper.assertTrue(copied.filterEntries() == 2, "Copier counts " + copied.filterEntries() + " entries (2)");
        helper.succeed();
    }
}
