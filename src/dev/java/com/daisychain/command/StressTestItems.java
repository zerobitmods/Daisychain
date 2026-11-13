package com.daisychain.command;

import com.daisychain.Daisychain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Test-only big storage for /daisychain stress bigitems (zerobit). Vanilla has no inventory bigger than a double chest
 * (54 slots), so a Nether Star upgrade (4,096 items = 64 stacks per transfer) already empties any vanilla source in one
 * go and a bigger item limit can't be measured with chests. In the development game only (never in a real modpack),
 * an enchanting table gets a {@value #SLOTS}-slot item storage, like the big modded storages (drawers, storage-system
 * interfaces) that a bigger limit would matter for.
 *
 * The storage lives in memory only: after the world is reloaded the tables are plain tables again, so build the test
 * again.
 *
 * Only the tables that /daisychain stress bigitems builds are crates (make); every other enchanting table stays plain.
 * Before step 116 every table was a crate, which broke the step 111 game tests that need a table without an inventory.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID)
public final class StressTestItems {
    /** True in the development game (./gradlew runClient), where the test crates exist. */
    public static final boolean AVAILABLE = !FMLEnvironment.production;
    /** Slots per test crate. */
    static final int SLOTS = 1024;
    /** Full stacks put into each crate (step 113: both ends, so pull sides rarely scan empty stretches). */
    static final int FILLED_STACKS = 400;
    private static final ItemStack[] FILL = {
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.DIRT, 64), new ItemStack(Items.SAND, 64)};

    private static final Map<BlockEntity, ItemStackHandler> CRATES = Collections.synchronizedMap(new WeakHashMap<>());

    private StressTestItems() {
    }

    /** Turns the enchanting table at pos into an empty test crate (call before placing the pipes next to it). */
    static void make(ServerLevel level, BlockPos pos) {
        BlockEntity table = level.getBlockEntity(pos);
        if (table == null) {
            return;
        }
        CRATES.computeIfAbsent(table, unused -> new ItemStackHandler(SLOTS));
        level.invalidateCapabilities(pos); // anything that already asked this table sees the crate now
    }

    /** Fills the crate at pos (made with make) with {@link #FILLED_STACKS} full stacks (cobblestone, dirt, sand). */
    static void fill(ServerLevel level, BlockPos pos) {
        BlockEntity table = level.getBlockEntity(pos);
        ItemStackHandler crate = table == null ? null : CRATES.get(table);
        if (crate == null) {
            return;
        }
        for (int slot = 0; slot < FILLED_STACKS; slot++) {
            crate.setStackInSlot(slot, FILL[slot % FILL.length].copy());
        }
    }

    // Capability registration is a mod-bus event; FML 1.21.1 sends it to the mod bus automatically.
    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        if (!AVAILABLE) {
            return;
        }
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BlockEntityType.ENCHANTING_TABLE,
                (blockEntity, side) -> CRATES.get(blockEntity)); // null for every table that isn't a crate
    }
}
