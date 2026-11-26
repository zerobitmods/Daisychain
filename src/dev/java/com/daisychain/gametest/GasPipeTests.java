package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.BasePipeBlock;
import com.daisychain.block.SideState;
import com.daisychain.compat.Chemicals;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
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
