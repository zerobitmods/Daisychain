package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.network.Loaded;
import com.daisychain.network.NetworkManager;
import com.daisychain.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Comparator;

/**
 * Review R17 (2026-11-10): chunks that lose their ticket, or get it back, while they stay in memory. The game sends no
 * load or unload event for that, so these tests make it happen far away from the other tests (in chunks of their own,
 * kept loaded by tickets of their own) and check both directions.
 * <p>
 * Layout, west to east along one row: [source chest][pulling pipe] at the edge of the near chunk, 16 pipes through the
 * chunk between, [last pipe][destination chest] in the far chunk. The near chunk's ticket keeps the near chunk ticking
 * and the middle chunk loaded; the far chunk has a ticket of its own that the test takes away and gives back.
 * Nothing here reads the far chunk through the level while it has no ticket (that would load it again): the chest and
 * the chunk are held as objects from when they were loaded.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChunkTicketTests {
    private static final String EMPTY = "empty";
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("daisychain_test", Comparator.comparingLong(ChunkPos::toLong));

    private ChunkTicketTests() {
    }

    /**
     * The far chunk loses its ticket (a player walked away; it stays in memory for a while). Pipes must stop delivering
     * into it: the game no longer marks such a chunk for saving when a chest in it changes.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600)
    public static void nothingEntersChunkWithoutTicket(GameTestHelper helper) {
        FarLine line = new FarLine(helper, 1000, 1000);
        line.whenFlowing(() -> {
            line.takeFarTicket();
            line.later(5, () -> {
                line.check(!line.farHasTicket(), "Test setup: the far chunk still has its ticket");
                line.farChunk.setUnsaved(false); // as if the game had just saved the chunk (it does every few seconds)
                int before = line.delivered();
                line.later(40, () -> {
                    int extra = line.delivered() - before;
                    boolean willBeSaved = line.farChunk.isUnsaved();
                    line.close();
                    helper.assertTrue(extra == 0, extra + " items went into a chest whose chunk has no ticket"
                            + (willBeSaved ? "" : "; the chunk was not marked for saving, so they would be lost"));
                    helper.succeed();
                });
            });
        });
    }

    /**
     * The far chunk loses its ticket, the network is rebuilt (it now stops at that chunk), then the chunk gets its
     * ticket back without ever having been unloaded. The network must reach the chest again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600)
    public static void chunkJoinsAgainWhenTicketReturns(GameTestHelper helper) {
        FarLine line = new FarLine(helper, 1000, 1040);
        line.whenFlowing(() -> {
            line.takeFarTicket();
            line.later(5, () -> {
                line.check(!line.farHasTicket(), "Test setup: the far chunk still has its ticket");
                // What placing or breaking any pipe of the network does: it is rebuilt, and now stops at the far chunk.
                NetworkManager.get(line.level).onPipeChanged(line.firstPipe);
                line.later(30, () -> {
                    int before = line.delivered();
                    line.giveFarTicket();
                    line.later(60, () -> {
                        int extra = line.delivered() - before;
                        line.close();
                        helper.assertTrue(extra > 0, "Nothing reached the chest after its chunk got its ticket back"
                                + " (the network stayed cut at that chunk)");
                        helper.succeed();
                    });
                });
            });
        });
    }

    /**
     * The far chunk's ticket level goes up but stays loaded (what every chunk behind a walking player does). The game
     * reports that as "unloaded, then loaded at the new level", so the listener sees the chunk cross the line twice;
     * the network must be left alone. Alone in its batch: it reads the whole server's counters.
     */
    @GameTest(template = EMPTY, batch = "ticketDip", timeoutTicks = 600)
    public static void ticketDipKeepsNetwork(GameTestHelper helper) {
        FarLine line = new FarLine(helper, 1000, 1080);
        line.whenFlowing(() -> {
            NetworkManager manager = NetworkManager.get(line.level);
            Object network = manager.getOrBuild(line.firstPipe);
            long[] before = NetworkManager.ticketCounters();
            line.chunks.addRegionTicket(TICKET, line.far, 2, line.far); // a second, stronger ticket: level 33 -> 31
            line.later(5, () -> {
                line.chunks.removeRegionTicket(TICKET, line.far, 2, line.far); // back up to 33, by way of "unloaded"
                line.later(5, () -> {
                    long[] after = NetworkManager.ticketCounters();
                    int count = line.delivered();
                    line.later(30, () -> {
                        int extra = line.delivered() - count;
                        boolean same = manager.getOrBuild(line.firstPipe) == network;
                        line.close();
                        helper.assertTrue(after[2] > before[2], "Test setup: no chunk with pipes crossed the line"
                                + " (the game no longer reports a rising level by way of 'unloaded'?)");
                        helper.assertTrue(after[3] == before[3] && same, "The network was rebuilt although no chunk"
                                + " stopped being loaded (" + (after[3] - before[3]) + " dropped)");
                        helper.assertTrue(extra > 0, "Items stopped moving after the ticket level changed");
                        helper.succeed();
                    });
                });
            });
        });
    }

    /** One row of pipes from a ticking chunk into a chunk two further east, far from everything else. */
    private static final class FarLine {
        private static final int PIPES = 18; // 1 in the near chunk, 16 through the middle chunk, 1 in the far chunk
        final GameTestHelper helper;
        final ServerLevel level;
        final ServerChunkCache chunks;
        final ChunkPos near;
        final ChunkPos far;
        final BlockPos source;
        final BlockPos firstPipe;
        final BlockPos destination;
        ChestBlockEntity destinationChest;
        LevelChunk farChunk;
        private int stage;
        private int ticks;
        private int laterAt;
        private Runnable later;
        private boolean nearTicket;
        private boolean farTicket;

        FarLine(GameTestHelper helper, int chunkX, int chunkZ) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.chunks = level.getChunkSource();
            this.near = new ChunkPos(chunkX, chunkZ);
            this.far = new ChunkPos(chunkX + 2, chunkZ);
            int y = helper.absolutePos(BlockPos.ZERO).getY() + 2;
            this.source = new BlockPos(near.getMaxBlockX() - 1, y, near.getMinBlockZ() + 8);
            this.firstPipe = source.east(); // the last column of the near chunk
            this.destination = firstPipe.east(PIPES); // the second column of the far chunk
        }

        /** Loads the chunks, builds the line, and runs next once the first items have arrived at the far chest. */
        void whenFlowing(Runnable next) {
            chunks.addRegionTicket(TICKET, near, 1, near); // near: ticking; the chunk east of it: loaded
            nearTicket = true;
            giveFarTicket();
            // Wait right here until the three chunks are there (they are made on other threads, and the test server
            // doesn't wait for them between ticks), so the test never depends on how fast that goes.
            level.getChunk(near.x, near.z);
            level.getChunk(near.x + 1, near.z);
            level.getChunk(far.x, far.z);
            helper.onEachTick(() -> {
                ticks++;
                if (stage == 0 && loaded(near.x) && loaded(near.x + 1) && loaded(far.x)) {
                    build();
                    stage = 1;
                } else if (stage == 1 && delivered() > 0) {
                    stage = 2;
                    next.run();
                } else if (stage < 2 && ticks > 200) {
                    int stuckAt = stage;
                    stage = 3;
                    check(false, stuckAt == 0 ? "Test setup: the chunks didn't load"
                            : "Test setup: nothing arrived at the far chest");
                } else if (later != null && ticks >= laterAt) {
                    Runnable action = later;
                    later = null;
                    action.run();
                }
            });
        }

        /**
         * Runs action this many ticks from now (one at a time). The steps of a test use this instead of the helper's
         * runAfterDelay: that one adds to the very list the game is walking through while a step runs, which is only
         * safe as long as the list happens not to grow.
         */
        void later(int delay, Runnable action) {
            laterAt = ticks + delay;
            later = action;
        }

        private boolean loaded(int chunkX) {
            return Loaded.chunk(level, chunkX, near.z) != null;
        }

        private void build() {
            for (int i = -1; i <= PIPES; i++) { // from the source to the destination: nothing left from an earlier run
                level.setBlock(firstPipe.east(i), Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(source, Blocks.CHEST.defaultBlockState(), 3);
            level.setBlock(destination, Blocks.CHEST.defaultBlockState(), 3);
            if (level.getBlockEntity(source) instanceof ChestBlockEntity chest) {
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                }
            }
            if (!(level.getBlockEntity(destination) instanceof ChestBlockEntity chest)) {
                throw new IllegalStateException("No chest at the far end");
            }
            destinationChest = chest;
            farChunk = level.getChunk(far.x, far.z);
            for (int i = 0; i < PIPES; i++) {
                level.setBlock(firstPipe.east(i), ModBlocks.ITEM_PIPE.get().defaultBlockState(), 3);
            }
            for (int i = 0; i < PIPES; i++) {
                FlowPipeBlock.refreshArms(level, firstPipe.east(i));
            }
            FlowPipeBlock.setPullSidesForTest(level, firstPipe, 1 << Direction.WEST.get3DDataValue());
            if (level.getBlockEntity(firstPipe) instanceof PipeDataBlockEntity data) {
                data.setUpgradeLevel(Direction.WEST, 1); // Iron: 16 items every 10 ticks
            }
        }

        /** Items in the far chest, read from the object (never through the level, see the class comment). */
        int delivered() {
            int total = 0;
            for (int slot = 0; slot < destinationChest.getContainerSize(); slot++) {
                total += destinationChest.getItem(slot).getCount();
            }
            return total;
        }

        boolean farHasTicket() {
            return chunks.hasChunk(far.x, far.z);
        }

        void giveFarTicket() {
            chunks.addRegionTicket(TICKET, far, 0, far);
            farTicket = true;
        }

        void takeFarTicket() {
            chunks.removeRegionTicket(TICKET, far, 0, far);
            farTicket = false;
        }

        /** Gives the tickets back so the chunks can unload. */
        void close() {
            if (farTicket) {
                takeFarTicket();
            }
            if (nearTicket) {
                chunks.removeRegionTicket(TICKET, near, 1, near);
                nearTicket = false;
            }
        }

        void check(boolean condition, String message) {
            if (!condition) {
                close();
                helper.fail(message);
            }
        }
    }
}
