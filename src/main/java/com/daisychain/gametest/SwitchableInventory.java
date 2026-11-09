package com.daisychain.gametest;

import com.daisychain.Daisychain;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Step 111: development game only (never in a real modpack). An enchanting table gets an inventory that a game test
 * can switch on and off without any block update, the way a Mekanism machine's Side Config does. Off by default, so
 * enchanting tables behave as in vanilla unless a test switches one on.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID)
public final class SwitchableInventory {
    private static final Map<BlockPos, ItemStackHandler> OPEN = new ConcurrentHashMap<>();

    private SwitchableInventory() {
    }

    /** Switches the table at pos (absolute) on or off. The caller then calls level.invalidateCapabilities(pos). */
    public static void set(BlockPos pos, boolean open) {
        if (open) {
            OPEN.computeIfAbsent(pos.immutable(), p -> new ItemStackHandler(1));
        } else {
            OPEN.remove(pos);
        }
    }

    // Capability registration is a mod-bus event; FML 1.21.1 sends it to the mod bus automatically.
    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        if (FMLEnvironment.production) {
            return;
        }
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BlockEntityType.ENCHANTING_TABLE,
                (table, side) -> OPEN.get(table.getBlockPos()));
    }
}
