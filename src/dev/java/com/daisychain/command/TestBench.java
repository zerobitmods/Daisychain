package com.daisychain.command;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.registry.ModBlocks;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 96 / F24 (zerobit): /daisychain testbench builds a row of labeled test stations next to the player, one per thing to
 * check by hand, so testing an update takes a few minutes. Each station has a sign (short title) and a line in chat
 * (what to do, what should happen). Development game only (registered next to "stress"; the energy stations need the
 * test source and sink from StressTestEnergy). Use it in a fresh superflat creative world: it clears the area it builds
 * in. New features get a new station here.
 */
final class TestBench {
    /** Blocks between the start of one station and the next. */
    private static final int STRIDE = 7;
    /** Depth (north of the sign row) and height cleared for every station. */
    private static final int DEPTH = 14;
    private static final int HEIGHT = 4;

    private TestBench() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("testbench").executes(context -> build(context.getSource()));
    }

    /** One station: where it starts, and the chat line explaining it. */
    private record Station(String title, String howTo) {
    }

    private static int build(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos feet = BlockPos.containing(source.getPosition());
        // Stations run east from the player; each one's sign is 2 blocks north of the player, the blocks behind it.
        BlockPos start = feet.offset(2, 0, -2);
        List<Station> stations = new ArrayList<>();
        int x = 0;

        clear(level, start.offset(-1, 0, -DEPTH), 10 * STRIDE + 2);

        stations.add(items(level, start.east(x)));
        x += STRIDE;
        stations.add(destinationFull(level, start.east(x)));
        x += STRIDE;
        stations.add(pullCap(level, start.east(x)));
        x += STRIDE;
        stations.add(swap(level, start.east(x)));
        x += STRIDE;
        stations.add(swapOnCap(level, start.east(x)));
        x += STRIDE;
        stations.add(tiers(level, start.east(x)));
        x += STRIDE;
        stations.add(fluids(level, start.east(x)));
        x += STRIDE;
        stations.add(universal(level, start.east(x)));
        x += STRIDE;
        stations.add(filter(level, start.east(x)));
        x += STRIDE;
        stations.add(ultimine(level, start.east(x)));

        source.sendSuccess(() -> Component.literal("Daisychain test bench: " + stations.size()
                + " stations, left to right (east of you). Each sign has a number:").withStyle(ChatFormatting.GOLD), false);
        for (int i = 0; i < stations.size(); i++) {
            Station station = stations.get(i);
            int number = i + 1;
            source.sendSuccess(() -> Component.literal(number + ". " + station.title() + ": ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(station.howTo()).withStyle(ChatFormatting.WHITE)), false);
        }
        return stations.size();
    }

    // ---------------------------------------------------------------- stations

    /** Chest -> 3 item pipes -> chest. */
    private static Station items(ServerLevel level, BlockPos sign) {
        sign(level, sign, "1 Items", "chest to chest");
        BlockPos a = sign.north(3);
        chest(level, a, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 64),
                new ItemStack(Items.SAND, 64));
        chest(level, a.east(4));
        line(level, a.east(), 3, ModBlocks.ITEM_PIPE.get());
        pull(level, a.east(), Direction.WEST);
        return new Station("Items", "cobble, dirt and sand move from the left chest to the right one. 192 items in"
                + " total afterwards, none lost or doubled.");
    }

    /** The destination is full: the source keeps everything. */
    private static Station destinationFull(ServerLevel level, BlockPos sign) {
        sign(level, sign, "2 Full", "destination");
        BlockPos a = sign.north(3);
        chest(level, a, new ItemStack(Items.DIRT, 64));
        ItemStack[] stone = new ItemStack[27];
        for (int i = 0; i < stone.length; i++) {
            stone[i] = new ItemStack(Items.STONE, 64);
        }
        chest(level, a.east(4), stone);
        line(level, a.east(), 3, ModBlocks.ITEM_PIPE.get());
        pull(level, a.east(), Direction.WEST);
        return new Station("Destination full", "the right chest is full of stone, so the 64 dirt stay in the left"
                + " chest. Take some stone out: dirt starts moving.");
    }

    /** A pull cap facing air, with pipes to click it with. */
    private static Station pullCap(ServerLevel level, BlockPos sign) {
        sign(level, sign, "3 Pull cap", "click / sneak");
        BlockPos pipe = sign.north(3).east(2);
        level.setBlock(pipe, ModBlocks.ITEM_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
        FlowPipeBlock.refreshArms(level, pipe);
        pull(level, pipe, Direction.SOUTH);
        chest(level, sign.north(3), new ItemStack(ModBlocks.ITEM_PIPE.get(), 64));
        return new Station("Pull cap", "take item pipes from the chest. Plain right-click the pipe's cap (facing you):"
                + " its settings open. Sneak + right-click the cap: a pipe is placed there and joins (the pull goes).");
    }

    /** Basic energy line with a configured pull side and an old upgrade, plus higher and lower tiers to swap with. */
    private static Station swap(ServerLevel level, BlockPos sign) {
        sign(level, sign, "4 Tier swap", "sneak + click");
        BlockPos a = sign.north(3);
        level.setBlock(a, Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(a.east(4), Blocks.LECTERN.defaultBlockState(), Block.UPDATE_ALL);
        Block basic = ModBlocks.energyPipes()[0];
        line(level, a.east(), 3, basic);
        pull(level, a.east(), Direction.WEST);
        if (level.getBlockEntity(a.east()) instanceof PipeDataBlockEntity data) {
            data.applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_BATCH, 1234, PipeKind.ENERGY_RES);
            data.setUpgradeLevel(Direction.WEST, 2); // an "old" Gold upgrade, as in worlds from before step 84
        }
        chest(level, a.south(), new ItemStack(ModBlocks.energyPipes()[2], 16),
                new ItemStack(ModBlocks.energyPipes()[0], 16));
        return new Station("Tier swap", "take Gold energy pipes from the chest. Sneak + right-click the MIDDLE of each"
                + " basic pipe: it turns Gold, you lose 1 Gold and get 1 basic back. The pipe next to the daylight"
                + " detector keeps Batch 1,234 (open its pull side) and its old Gold upgrade (sneak + right-click it"
                + " with an empty hand: it pops out, once). Sneak-clicking with a basic pipe places it instead.");
    }

    /** An energy pipe whose pull cap faces air: sneak-clicking the cap with a higher tier places, not swaps. */
    private static Station swapOnCap(ServerLevel level, BlockPos sign) {
        sign(level, sign, "5 Swap vs cap", "sneak on cap");
        BlockPos pipe = sign.north(3).east(2);
        level.setBlock(pipe, ModBlocks.energyPipes()[0].defaultBlockState(), Block.UPDATE_ALL);
        FlowPipeBlock.refreshArms(level, pipe);
        pull(level, pipe, Direction.SOUTH);
        chest(level, sign.north(3), new ItemStack(ModBlocks.energyPipes()[2], 16));
        return new Station("Swap vs pull cap", "with a Gold energy pipe, sneak + right-click the cap facing you: a pipe"
                + " is placed there (no swap). Break it, then sneak + right-click the pipe's middle: it swaps to Gold.");
    }

    /** Every energy tier, each moving power (daylight detector = endless source, lectern = endless sink). */
    private static Station tiers(ServerLevel level, BlockPos sign) {
        sign(level, sign, "6 Energy", "all tiers");
        Block[] tiers = ModBlocks.energyPipes();
        for (int t = 0; t < tiers.length; t++) {
            BlockPos a = sign.north(3 + t * 2);
            level.setBlock(a, Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(a.east(4), Blocks.LECTERN.defaultBlockState(), Block.UPDATE_ALL);
            line(level, a.east(), 3, tiers[t]);
            pull(level, a.east(), Direction.WEST);
        }
        return new Station("Energy tiers", "basic (front) to nether star (back), each moving power. Check the colors,"
                + " the glow at night (/time set night), and Jade's speed on each pull side.");
    }

    /** Full water cauldron -> fluid pipes -> empty cauldron (whole buckets only, so the pull side has an Iron upgrade). */
    private static Station fluids(ServerLevel level, BlockPos sign) {
        sign(level, sign, "7 Fluids", "cauldrons");
        BlockPos a = sign.north(3);
        level.setBlock(a, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3),
                Block.UPDATE_ALL);
        level.setBlock(a.east(4), Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
        line(level, a.east(), 3, ModBlocks.FLUID_PIPE.get());
        pull(level, a.east(), Direction.WEST);
        if (level.getBlockEntity(a.east()) instanceof PipeDataBlockEntity data) {
            data.setUpgradeLevel(Direction.WEST, 1);
        }
        return new Station("Fluids", "the water moves from the left cauldron to the right one (a whole bucket at a"
                + " time). Refill the left one with a water bucket to send more.");
    }

    /** Chest and endless source -> universal pipes -> chest and endless sink. */
    private static Station universal(ServerLevel level, BlockPos sign) {
        sign(level, sign, "8 Universal", "items + energy");
        BlockPos a = sign.north(3);
        chest(level, a, new ItemStack(Items.COBBLESTONE, 64));
        chest(level, a.east(4));
        line(level, a.east(), 3, ModBlocks.UNIVERSAL_PIPE.get());
        pull(level, a.east(), Direction.WEST);
        return new Station("Universal", "the cobble moves to the right chest through universal pipes.");
    }

    /** Mixed items with a pull side to set a filter on. */
    private static Station filter(ServerLevel level, BlockPos sign) {
        sign(level, sign, "9 Filter", "set it yourself");
        BlockPos a = sign.north(3);
        chest(level, a, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.GOLD_INGOT, 64),
                new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.GOLDEN_SWORD), new ItemStack(Items.IRON_SWORD));
        chest(level, a.east(4));
        line(level, a.east(), 3, ModBlocks.ITEM_PIPE.get());
        pull(level, a.east(), Direction.WEST);
        if (level.getBlockEntity(a.east()) instanceof PipeDataBlockEntity data) {
            // Redstone "With signal" (RedstoneMode.ON_WITH_SIGNAL): nothing moves until the lever is switched on.
            data.applySetting(Direction.WEST, PipeDataBlockEntity.SETTING_REDSTONE, 1, PipeKind.ITEMS);
        }
        level.setBlock(a.east().south(), Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
        return new Station("Filter", "nothing moves until you flip the lever (the pull side is set to With signal)."
                + " Set a filter first, then flip it. Try: Tag #c:ingots (forge:ingots on 1.20.1) lets only the ingots"
                + " through; a golden sword with Tag minecraft:swords lets both swords through.");
    }

    /** Step 98: a basic energy line to swap all at once with FTB Ultimine (installed in the dev game). */
    private static Station ultimine(ServerLevel level, BlockPos sign) {
        sign(level, sign, "10 Ultimine", "swap a line");
        BlockPos a = sign.north(3);
        level.setBlock(a, Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(a.east(5), Blocks.LECTERN.defaultBlockState(), Block.UPDATE_ALL);
        line(level, a.east(), 4, ModBlocks.energyPipes()[0]);
        pull(level, a.east(), Direction.WEST);
        chest(level, a.south(), new ItemStack(ModBlocks.energyPipes()[2], 3), new ItemStack(ModBlocks.energyPipes()[3], 16));
        return new Station("Ultimine", "hold the Ultimine key (default `, the key above Tab) and right-click a basic pipe"
                + " with the 3 Gold pipes from the chest: 3 of the 4 turn Gold (you run out), the line keeps working,"
                + " you get 3 basic back. Then do it with Diamond: all 4 go up. Without the key, only sneak-clicking swaps.");
    }

    // ---------------------------------------------------------------- helpers

    /** Clears a box (air) above the ground in front of the player, so stations never mix with what was there. */
    private static void clear(ServerLevel level, BlockPos corner, int width) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz <= DEPTH; dz++) {
                for (int dy = 0; dy < HEIGHT; dy++) {
                    cursor.set(corner.getX() + dx, corner.getY() + dy, corner.getZ() + dz);
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** A row of pipes going east, with their arms worked out once everything around them is placed. */
    private static void line(ServerLevel level, BlockPos first, int length, Block pipe) {
        for (int i = 0; i < length; i++) {
            level.setBlock(first.east(i), pipe.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (int i = 0; i < length; i++) {
            FlowPipeBlock.refreshArms(level, first.east(i));
        }
    }

    private static void pull(ServerLevel level, BlockPos pipe, Direction side) {
        FlowPipeBlock.setPullSidesForTest(level, pipe, 1 << side.get3DDataValue());
    }

    private static void chest(ServerLevel level, BlockPos pos, ItemStack... contents) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < contents.length && i < chest.getContainerSize(); i++) {
                chest.setItem(i, contents[i]);
            }
        }
    }

    /** A standing sign facing the player (south), with two short lines. */
    private static void sign(ServerLevel level, BlockPos pos, String line1, String line2) {
        level.setBlock(pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0),
                Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText()
                    .setMessage(0, Component.literal(line1))
                    .setMessage(1, Component.literal(line2));
            sign.setText(text, true);
            sign.setChanged();
            level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
