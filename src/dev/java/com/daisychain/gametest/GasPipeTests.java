package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.block.SideState;
import com.daisychain.compat.Chemicals;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Step F95 (port of step 171): the gas pipe block and the Mekanism hookup. With Mekanism a gas pipe connects to a chemical tank (found by
 * its registry name, so this class names no Mekanism class) and not to a chest; gas pipes link with each other with or
 * without Mekanism. With the hookup switched off (as if a Mekanism update broke it), gas pipes don't connect to the
 * tank and item pipes still move items.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GasPipeTests {
    private static final String EMPTY = "empty";
    private static final ResourceLocation TANK = new ResourceLocation("mekanism", "basic_chemical_tank");

    private GasPipeTests() {
    }

    private static SideState side(GameTestHelper helper, BlockPos pos, Direction direction) {
        return helper.getLevel().getBlockState(helper.absolutePos(pos)).getValue(BasePipeBlock.propertyFor(direction));
    }

    /** A gas pipe with a chemical tank north (only with Mekanism), a chest south and another gas pipe east. */
    private static void gasPipeWithNeighbours(GameTestHelper helper, BlockPos pipe) {
        if (Chemicals.mekanismLoaded()) {
            Block tank = BuiltInRegistries.BLOCK.get(TANK);
            helper.assertTrue(tank != Blocks.AIR, "Setup: Mekanism is loaded but has no " + TANK);
            helper.setBlock(pipe.north(), tank.defaultBlockState());
            chemicalSidesOn(helper, pipe.north());
        }
        helper.setBlock(pipe.south(), Blocks.CHEST.defaultBlockState());
        helper.setBlock(pipe.east(), ModBlocks.GAS_PIPE.get().defaultBlockState());
        helper.setBlock(pipe, ModBlocks.GAS_PIPE.get().defaultBlockState());
        for (BlockPos pos : new BlockPos[] {pipe, pipe.east()}) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(pos));
        }
    }

    /**
     * Switches every side of the four chemical side configs (Mekanism 10.4: "config2" gas .. "config5" slurry, each a
     * compound side0..side5) to input / output (value 7), the way a player sets them in the side config screen. (On
     * 1.20.1 a tank placed by command already has Mekanism's defaults, front output and the rest input; on 1.21.1 it
     * has every side off, so the NeoForge test needs this. Kept here so both test the same tank.)
     */
    static void chemicalSidesOn(GameTestHelper helper, BlockPos pos) {
        BlockPos at = helper.absolutePos(pos);
        BlockEntity blockEntity = helper.getLevel().getBlockEntity(at);
        helper.assertTrue(blockEntity != null, "Setup: no block entity at " + pos);
        CompoundTag tag = blockEntity.saveWithoutMetadata();
        CompoundTag config = tag.getCompound("componentConfig");
        for (int type = 2; type <= 5; type++) {
            CompoundTag sides = new CompoundTag(); // 10.4 saves each side config as side0..side5
            for (int side = 0; side < 6; side++) {
                sides.putInt("side" + side, 7);
            }
            config.put("config" + type, sides);
        }
        tag.put("componentConfig", config);
        blockEntity.load(tag);
        blockEntity.invalidateCaps();
        blockEntity.reviveCaps();
    }

    @GameTest(template = EMPTY)
    public static void gasPipeConnects(GameTestHelper helper) {
        BlockPos pipe = new BlockPos(4, 1, 2);
        gasPipeWithNeighbours(helper, pipe);
        if (Chemicals.mekanismLoaded()) {
            helper.assertTrue(side(helper, pipe, Direction.NORTH) == SideState.CONNECTED, "No arm to the chemical tank");
        }
        helper.assertTrue(side(helper, pipe, Direction.SOUTH) == SideState.NONE, "Arm into a chest");
        helper.assertTrue(side(helper, pipe, Direction.EAST) == SideState.CONNECTED, "Gas pipes didn't link");
        helper.succeed();
    }

    // ---------------------------------------------------------------- step 173: recipe only with Mekanism

    /**
     * The gas pipe recipe loads only with Mekanism: steel / glass, circuit, glass / steel makes 8 (step 175; was 16), with Mekanism's own
     * steel and Basic Control Circuit (found through the forge:ingots/steel and forge:circuits/basic tags).
     */
    @GameTest(template = EMPTY)
    public static void gasPipeRecipeOnlyWithMekanism(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        boolean loaded = level.getRecipeManager()
                .byKey(new ResourceLocation(Daisychain.MOD_ID, "gas_pipe")).isPresent();
        helper.assertTrue(loaded == Chemicals.mekanismLoaded(), "Gas pipe recipe loaded: " + loaded + ", Mekanism: "
                + Chemicals.mekanismLoaded());
        if (Chemicals.mekanismLoaded()) {
            ItemStack steel = new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation("mekanism", "ingot_steel")));
            ItemStack circuit = new ItemStack(BuiltInRegistries.ITEM.get(
                    new ResourceLocation("mekanism", "basic_control_circuit")));
            ItemStack glass = new ItemStack(Items.GLASS);
            // No menu: the items go in through the constructor.
            TransientCraftingContainer input = new TransientCraftingContainer(null, 3, 3, NonNullList.of(ItemStack.EMPTY,
                    steel.copy(), steel.copy(), steel.copy(), glass.copy(), circuit, glass.copy(), steel.copy(),
                    steel.copy(), steel.copy()));
            ItemStack out = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level)
                    .map(recipe -> recipe.assemble(input, level.registryAccess())).orElse(ItemStack.EMPTY);
            helper.assertTrue(out.is(ModItems.GAS_PIPE.get()) && out.getCount() == 8, "Crafted " + out);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- hookup switched off (own batch: it's global)

    @BeforeBatch(batch = "gasHookupOff")
    public static void hookupOff(ServerLevel level) {
        Chemicals.setBrokenForTest(true);
    }

    @AfterBatch(batch = "gasHookupOff")
    public static void hookupOn(ServerLevel level) {
        Chemicals.setBrokenForTest(false);
    }

    @GameTest(template = EMPTY, batch = "gasHookupOff", timeoutTicks = 200)
    public static void hookupOffRestKeepsWorking(GameTestHelper helper) {
        helper.assertFalse(Chemicals.available(), "Setup: gas is still on");
        BlockPos pipe = new BlockPos(4, 1, 2);
        gasPipeWithNeighbours(helper, pipe);
        helper.assertTrue(side(helper, pipe, Direction.NORTH) == SideState.NONE, "Arm to the tank with gas off");
        helper.assertTrue(side(helper, pipe, Direction.EAST) == SideState.CONNECTED, "Gas pipes stopped linking");
        // Items still move: chest -> 3 item pipes -> chest.
        BlockPos from = new BlockPos(1, 1, 5);
        BlockPos to = new BlockPos(5, 1, 5);
        helper.setBlock(from, Blocks.CHEST.defaultBlockState());
        helper.setBlock(to, Blocks.CHEST.defaultBlockState());
        if (helper.getLevel().getBlockEntity(helper.absolutePos(from)) instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        }
        for (int i = 1; i <= 3; i++) {
            helper.setBlock(from.east(i), ModBlocks.ITEM_PIPE.get().defaultBlockState());
        }
        for (int i = 1; i <= 3; i++) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(from.east(i)));
        }
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(from.east()),
                1 << Direction.WEST.get3DDataValue());
        helper.succeedWhen(() -> helper.assertTrue(count(helper, to) == 64, "Items moved: " + count(helper, to)));
    }

    // ---------------------------------------------------------------- step 172: chemicals move

    private static final BlockPos TANK_A = new BlockPos(1, 1, 2);
    private static final BlockPos TANK_B = new BlockPos(5, 1, 2);

    /** Tank A (filled with hydrogen) -> three gas pipes, pulling from A -> tank B (or another block at B). */
    private static void gasLine(GameTestHelper helper, long hydrogen, @javax.annotation.Nullable Block atB) {
        gasLine(helper, hydrogen, atB, ModBlocks.GAS_PIPE.get());
    }

    private static void gasLine(GameTestHelper helper, long hydrogen, @javax.annotation.Nullable Block atB, Block pipe) {
        Block tank = BuiltInRegistries.BLOCK.get(TANK);
        helper.setBlock(TANK_A, tank.defaultBlockState());
        chemicalSidesOn(helper, TANK_A);
        if (atB == null) {
            helper.setBlock(TANK_B, tank.defaultBlockState());
            chemicalSidesOn(helper, TANK_B);
        } else {
            helper.setBlock(TANK_B, atB.defaultBlockState());
        }
        if (hydrogen > 0) {
            long in = MekanismTestKit.fill(helper.getLevel(), helper.absolutePos(TANK_A), "mekanism:hydrogen", hydrogen);
            helper.assertTrue(in == hydrogen, "Setup: tank A took " + in + " mB");
        }
        for (int x = 2; x <= 4; x++) {
            helper.setBlock(new BlockPos(x, 1, 2), pipe.defaultBlockState());
        }
        for (int x = 2; x <= 4; x++) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 2)));
        }
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)),
                1 << Direction.WEST.get3DDataValue());
    }

    private static long gas(GameTestHelper helper, BlockPos pos) {
        return MekanismTestKit.amount(helper.getLevel(), helper.absolutePos(pos));
    }

    /** All the hydrogen goes from A to B; at no tick is there more than there was. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void gasMovesBetweenTanks(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        gasLine(helper, 8_000, null);
        helper.onEachTick(() -> {
            long total = gas(helper, TANK_A) + gas(helper, TANK_B);
            if (total != 8_000) {
                helper.fail("Hydrogen " + (total > 8_000 ? "made" : "lost") + ": " + total + " mB (should be 8000)");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(gas(helper, TANK_B) == 8_000, "Tank B has " + gas(helper, TANK_B));
            helper.assertTrue(gas(helper, TANK_A) == 0, "Tank A still has " + gas(helper, TANK_A));
        });
    }

    /**
     * Not only gases: 4000 mB of redstone infusion goes from A to B (on 1.20.1 infusion is its own chemical type with
     * its own handler; on 1.21.1 one of the chemicals).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void infusionMoves(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        gasLine(helper, 0, null);
        long in = MekanismTestKit.fillInfusion(helper.getLevel(), helper.absolutePos(TANK_A), "mekanism:redstone", 4_000);
        helper.assertTrue(in == 4_000, "Setup: tank A took " + in + " mB of redstone");
        helper.succeedWhen(() -> {
            long b = MekanismTestKit.amountInfusion(helper.getLevel(), helper.absolutePos(TANK_B));
            long a = MekanismTestKit.amountInfusion(helper.getLevel(), helper.absolutePos(TANK_A));
            helper.assertTrue(b == 4_000 && a == 0, "Redstone: A " + a + ", B " + b + " (should be 0 and 4000)");
        });
    }

    /** Step 176: a universal pipe line carries hydrogen too (a new pull side has chemicals on). */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void universalCarriesGas(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        gasLine(helper, 8_000, null, ModBlocks.UNIVERSAL_PIPE.get());
        helper.succeedWhen(() -> {
            helper.assertTrue(gas(helper, TANK_B) == 8_000, "Tank B has " + gas(helper, TANK_B));
            helper.assertTrue(gas(helper, TANK_A) == 0, "Tank A still has " + gas(helper, TANK_A));
        });
    }

    /** Keep in source 3000 mB: A stops at 3000, B gets the other 5000. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void gasKeepsInSource(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        gasLine(helper, 8_000, null);
        BlockPos pull = helper.absolutePos(new BlockPos(2, 1, 2));
        PipeDataBlockEntity data = BasePipeBlock.getData(helper.getLevel(), pull, helper.getLevel().getBlockState(pull));
        helper.assertTrue(data != null, "Setup: no pull side settings");
        data.applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_KEEP, 3_000, PipeKind.CHEMICALS);
        helper.assertTrue(data.keepInSource(Direction.WEST, PipeKind.CHEMICALS) == 3_000, "Setup: Keep didn't stick");
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(gas(helper, TANK_A) == 3_000, "Tank A has " + gas(helper, TANK_A) + " (keep 3000)");
            helper.assertTrue(gas(helper, TANK_B) == 5_000, "Tank B has " + gas(helper, TANK_B));
            helper.succeed();
        });
    }

    @BeforeBatch(batch = "gasThrows")
    public static void beforeThrows(ServerLevel level) {
        Chemicals.setBrokenForTest(false);
        if (Chemicals.mekanismLoaded()) {
            MekanismTestKit.throwingArmed = true;
        }
    }

    @AfterBatch(batch = "gasThrows")
    public static void afterThrows(ServerLevel level) {
        if (Chemicals.mekanismLoaded()) {
            MekanismTestKit.throwingArmed = false;
        }
        Chemicals.setBrokenForTest(false);
    }

    /**
     * A destination that accepts in simulation but throws when the hydrogen really arrives (a barrel with the test
     * handler): gas switches off, and the hydrogen taken out goes back into tank A.
     */
    @GameTest(template = EMPTY, batch = "gasThrows", timeoutTicks = 100)
    public static void throwingHandlerSwitchesGasOff(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        gasLine(helper, 1_000, Blocks.BARREL);
        helper.succeedWhen(() -> {
            helper.assertFalse(Chemicals.available(), "Gas is still on after a handler threw");
            helper.assertTrue(gas(helper, TANK_A) == 1_000, "Tank A has " + gas(helper, TANK_A) + " (should be back to 1000)");
            // Finished tests stay in the world: without this the barrel would switch gas off again in later batches.
            helper.setBlock(TANK_B, Blocks.AIR.defaultBlockState());
        });
    }

    @BeforeBatch(batch = "gasBreaksMidRun")
    public static void beforeBreak(ServerLevel level) {
        Chemicals.setBrokenForTest(false);
    }

    @AfterBatch(batch = "gasBreaksMidRun")
    public static void afterBreak(ServerLevel level) {
        Chemicals.setBrokenForTest(false);
    }

    /**
     * Gas and items moving; the hookup breaks half way (as if Mekanism changed): gas stops where it is (nothing lost),
     * items keep moving.
     */
    @GameTest(template = EMPTY, batch = "gasBreaksMidRun", timeoutTicks = 200)
    public static void hookupBreaksMidRun(GameTestHelper helper) {
        if (!Chemicals.mekanismLoaded()) {
            helper.succeed();
            return;
        }
        gasLine(helper, 60_000, null);
        BlockPos from = new BlockPos(1, 1, 5);
        BlockPos to = new BlockPos(5, 1, 5);
        helper.setBlock(from, Blocks.CHEST.defaultBlockState());
        helper.setBlock(to, Blocks.CHEST.defaultBlockState());
        if (helper.getLevel().getBlockEntity(helper.absolutePos(from)) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < 9; i++) {
                chest.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            }
        }
        for (int i = 1; i <= 3; i++) {
            helper.setBlock(from.east(i), ModBlocks.ITEM_PIPE.get().defaultBlockState());
        }
        for (int i = 1; i <= 3; i++) {
            BasePipeBlock.refreshArms(helper.getLevel(), helper.absolutePos(from.east(i)));
        }
        BasePipeBlock.setPullSidesForTest(helper.getLevel(), helper.absolutePos(from.east()),
                1 << Direction.WEST.get3DDataValue());
        long[] atBreak = new long[2];
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(gas(helper, TANK_B) > 0, "Setup: no gas moved before the break (gas on: "
                    + Chemicals.available() + ", A " + gas(helper, TANK_A) + ", items moved " + count(helper, to) + ")");
            Chemicals.setBrokenForTest(true);
            atBreak[0] = gas(helper, TANK_B);
            atBreak[1] = count(helper, to);
        });
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(gas(helper, TANK_A) + gas(helper, TANK_B) == 60_000,
                    "Hydrogen total " + (gas(helper, TANK_A) + gas(helper, TANK_B)));
            helper.assertTrue(gas(helper, TANK_B) == atBreak[0], "Gas kept moving after the break");
            helper.assertTrue(count(helper, to) > atBreak[1], "Items stopped after the gas hookup broke");
            helper.succeed();
        });
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
}
