package com.daisychain.command;

import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.registry.ModBlocks;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/**
 * /daisychain stress (step 35): builds many busy test networks around the player, to measure the mod's cost with
 * /daisychain stats, /tick query and the spark profiler. Operators only; meant for a throwaway superflat test world.
 *
 * Each network moves its contents back and forth forever between two ends A and B. Pull sides aren't destinations, so
 * each end has two pipes: one beside it that pulls, and one on top of it that inserts (joined by a corner pipe):
 * <pre>
 *   y+1:  [in A][corner] . . . . [corner][in B]
 *   y:    [ A  ][pull A][pipe ...][pull B][ B  ]
 * </pre>
 * - items / universal: A is a chest a little under half full (cobblestone, dirt, sand), B an empty chest;
 * - fluids: A a full water cauldron, B an empty one (needs an Iron upgrade or better: cauldrons move whole buckets).
 * - idle (step 37): like items, but both chests start empty, so every pull side only finds an empty chest each time.
 * Universal pipes carry only items here, but still check for fluids and energy every transfer, like in a real base.
 * Networks sit two blocks apart so they never touch, in a square centred on the player, two blocks below their feet.
 *
 * /daisychain stress huge (step 37) builds ONE big network instead (see buildHuge), to measure what a rebuild costs when
 * a pipe in it is broken or placed.
 * Building loads (and if needed generates) the chunks it builds in, which is fine for a one-off test command; the pipes
 * themselves never load chunks.
 */
final class StressTest {
    private static final int MAX_NETWORKS = 2000;
    private static final int MAX_LENGTH = 64;
    private static final int MIN_HUGE_PIPES = 100;
    private static final int MAX_HUGE_PIPES = 20000;
    /** In the huge network, one chest station every this many pipes along each row. */
    private static final int STATION_SPACING = 8;
    private static final ItemStack[] FILL = {
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 64), new ItemStack(Items.SAND, 64)};

    private StressTest() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("stress");
        for (String kind : new String[]{"items", "fluids", "universal", "idle", "energy"}) {
            root.then(Commands.literal(kind)
                    .then(Commands.argument("networks", IntegerArgumentType.integer(1, MAX_NETWORKS))
                            .then(Commands.argument("length", IntegerArgumentType.integer(2, MAX_LENGTH))
                                    .then(Commands.argument("upgrade", IntegerArgumentType.integer(0, 5))
                                            .executes(context -> build(context.getSource(), kind,
                                                    IntegerArgumentType.getInteger(context, "networks"),
                                                    IntegerArgumentType.getInteger(context, "length"),
                                                    IntegerArgumentType.getInteger(context, "upgrade")))))));
        }
        root.then(Commands.literal("huge")
                .then(Commands.argument("pipes", IntegerArgumentType.integer(MIN_HUGE_PIPES, MAX_HUGE_PIPES))
                        .then(Commands.argument("upgrade", IntegerArgumentType.integer(0, 5))
                                .executes(context -> buildHuge(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "pipes"),
                                        IntegerArgumentType.getInteger(context, "upgrade"))))));
        return root;
    }

    private static int build(CommandSourceStack source, String kind, int networks, int length, int upgrade) {
        ServerLevel level = source.getLevel();
        boolean fluids = kind.equals("fluids");
        boolean universal = kind.equals("universal");
        boolean empty = kind.equals("idle");
        boolean energy = kind.equals("energy");
        if (energy && !StressTestEnergy.AVAILABLE) {
            source.sendFailure(Component.literal(
                    "The energy stress test needs the development game (./gradlew runClient): its test batteries only exist there."));
            return 0;
        }
        if (fluids && upgrade == 0) {
            upgrade = 1; // cauldrons only take whole buckets (1000 mB); without an upgrade a transfer is 250 mB
        }
        // Energy (step 87): the upgrade number picks the energy pipe tier (0 basic ... 5 nether star).
        Block pipe = energy ? ModBlocks.energyPipes()[upgrade] : fluids ? ModBlocks.FLUID_PIPE.get()
                : universal ? ModBlocks.UNIVERSAL_PIPE.get() : ModBlocks.ITEM_PIPE.get();

        // A square-ish block of networks: each is (length + 2) long plus a 2-block gap, and rows are 2 apart.
        int stride = length + 4;
        int rows = Math.max(1, (int) Math.ceil(Math.sqrt(networks * stride / 2.0)));
        int columns = (networks + rows - 1) / rows;
        BlockPos feet = BlockPos.containing(source.getPosition());
        BlockPos origin = feet.offset(-(columns * stride) / 2, -2, -rows);

        for (int n = 0; n < networks; n++) {
            BlockPos a = origin.offset((n / rows) * stride, 0, (n % rows) * 2);
            if (energy) {
                buildEnergy(level, a, length, pipe);
            } else {
                buildOne(level, a, length, pipe, fluids, empty, upgrade);
            }
        }
        String message = String.format(Locale.ROOT,
                "Built %d %s networks (%d pipes each, upgrade level %d), %d x %d blocks, 2 below you. "
                        + "Measure with /daisychain stats and /tick query.",
                networks, kind, length + 4, upgrade, columns * stride, rows * 2);
        source.sendSuccess(() -> Component.literal(message), true);
        return networks;
    }

    private static void buildOne(ServerLevel level, BlockPos a, int length, Block pipe, boolean fluids,
                                 boolean empty, int upgrade) {
        BlockPos b = a.east(length + 1);
        // Clear the strip and the row above it.
        for (int i = 0; i <= length + 1; i++) {
            level.setBlock(a.east(i), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(a.east(i).above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (fluids) {
            level.setBlock(a, fullCauldron(), Block.UPDATE_ALL);
            level.setBlock(b, Blocks.CAULDRON.defaultBlockState(), Block.UPDATE_ALL);
        } else {
            level.setBlock(a, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(b, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
            if (!empty) {
                fillChest(level, a);
            }
        }
        BlockState pipeState = pipe.defaultBlockState();
        BlockPos[] pipes = new BlockPos[length + 4];
        int count = 0;
        for (int i = 1; i <= length; i++) {
            pipes[count++] = a.east(i);                  // the main line, beside A ... beside B
        }
        pipes[count++] = a.above();                      // inserts into A from above
        pipes[count++] = a.east(1).above();              // joins it to the line
        pipes[count++] = a.east(length).above();         // joins the B end
        pipes[count++] = b.above();                      // inserts into B from above
        for (BlockPos pos : pipes) {
            level.setBlock(pos, pipeState, Block.UPDATE_ALL);
        }
        for (BlockPos pos : pipes) {
            FlowPipeBlock.refreshArms(level, pos); // work out each pipe's arms now that everything is placed
        }
        pull(level, a.east(1), bit(Direction.WEST), upgrade);
        pull(level, a.east(length), bit(Direction.EAST), upgrade);
    }

    /**
     * Step 87: one energy network: [source][pipe x length][sink], pulling from the source (a daylight detector, an
     * endless source in the development game) into the sink (a lectern, an endless sink). Moves energy every tick.
     */
    private static void buildEnergy(ServerLevel level, BlockPos a, int length, Block pipe) {
        BlockPos b = a.east(length + 1);
        for (int i = 0; i <= length + 1; i++) {
            level.setBlock(a.east(i), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(a.east(i).above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(a, Blocks.DAYLIGHT_DETECTOR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(b, Blocks.LECTERN.defaultBlockState(), Block.UPDATE_ALL);
        BlockState pipeState = pipe.defaultBlockState();
        for (int i = 1; i <= length; i++) {
            level.setBlock(a.east(i), pipeState, Block.UPDATE_ALL);
        }
        for (int i = 1; i <= length; i++) {
            FlowPipeBlock.refreshArms(level, a.east(i));
        }
        FlowPipeBlock.setPullSidesForTest(level, a.east(1), bit(Direction.WEST));
    }

    /**
     * One item network of about the given number of pipes, laid out as rows joined at alternating ends (a snake),
     * centred on the player. Every STATION_SPACING pipes along a row there is a station: a chest under the row, which
     * the row pipe above it pulls from, and a second pipe beside the chest (joined to the row above it) that inserts
     * into it. So every chest both sends and receives, and items keep moving between stations forever:
     * <pre>
     *   y:     [row][row][pull][row][row] ...      (pull = row pipe pulling down from the chest)
     *   y-1:             [chest][in]               (in = pipe inserting into the chest, joined up to the row)
     * </pre>
     * Pulls never send back into their own chest (Send back is off), so items travel to other stations.
     */
    private static int buildHuge(CommandSourceStack source, int target, int upgrade) {
        ServerLevel level = source.getLevel();
        Block pipe = ModBlocks.ITEM_PIPE.get();
        BlockState pipeState = pipe.defaultBlockState();

        // Roughly square: rows are 2 apart, so a row is about twice as long as the number of rows.
        int rowLength = Math.max(STATION_SPACING + 2, (int) Math.ceil(Math.sqrt(target * 2.0)));
        int rows = Math.max(1, (target + rowLength - 1) / rowLength);
        BlockPos feet = BlockPos.containing(source.getPosition());
        BlockPos origin = feet.offset(-rowLength / 2, -1, -rows); // pipes 1 below the feet, chests 2 below

        // Clear the pipe layer and the layer above it, so nothing left over from earlier tests touches the pipes.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -1; x <= rowLength; x++) {
            for (int z = -1; z <= rows * 2; z++) {
                for (int y = 0; y <= 1; y++) {
                    level.setBlock(cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }

        java.util.List<BlockPos> pipes = new java.util.ArrayList<>();
        java.util.List<BlockPos> pulls = new java.util.ArrayList<>();
        for (int row = 0; row < rows; row++) {
            BlockPos rowStart = origin.offset(0, 0, row * 2);
            for (int x = 0; x < rowLength; x++) {
                BlockPos pos = rowStart.east(x);
                pipes.add(pos);
                if (x % STATION_SPACING == 3 && x + 1 < rowLength) {
                    // Station: chest under this pipe (pulled from), insert pipe beside the chest.
                    BlockPos chest = pos.below();
                    level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
                    fillChest(level, chest);
                    pipes.add(chest.east());
                    pulls.add(pos);
                }
            }
            if (row + 1 < rows) {
                // Join this row to the next one at alternating ends.
                pipes.add(rowStart.offset(row % 2 == 0 ? rowLength - 1 : 0, 0, 1));
            }
        }
        for (BlockPos pos : pipes) {
            level.setBlock(pos, pipeState, Block.UPDATE_ALL);
        }
        for (BlockPos pos : pipes) {
            FlowPipeBlock.refreshArms(level, pos);
        }
        for (BlockPos pos : pulls) {
            pull(level, pos, bit(Direction.DOWN), upgrade);
        }

        BlockPos middle = pipes.get(pipes.size() / 2);
        String message = String.format(Locale.ROOT,
                "Built one item network: %d pipes, %d chest stations (each pulled from and inserted into), "
                        + "upgrade level %d, %d x %d blocks, 1 below you. A pipe near the middle: %d %d %d. "
                        + "Test a rebuild: /daisychain stats, break a pipe, /daisychain stats.",
                pipes.size(), pulls.size(), upgrade, rowLength, rows * 2,
                middle.getX(), middle.getY(), middle.getZ());
        source.sendSuccess(() -> Component.literal(message), true);
        return pipes.size();
    }

    private static void fillChest(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            for (int slot = 0; slot < 13; slot++) {
                chest.setItem(slot, FILL[slot % FILL.length].copy());
            }
        }
    }

    private static void pull(ServerLevel level, BlockPos pos, int sides, int upgrade) {
        FlowPipeBlock.setPullSidesForTest(level, pos, sides);
        if (upgrade > 0 && level.getBlockEntity(pos) instanceof PipeDataBlockEntity data) {
            for (Direction side : Direction.values()) {
                if ((sides & bit(side)) != 0) {
                    data.setUpgradeLevel(side, upgrade);
                }
            }
        }
    }

    private static BlockState fullCauldron() {
        return Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3);
    }

    private static int bit(Direction direction) {
        return 1 << direction.get3DDataValue();
    }
}
