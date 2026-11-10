package com.daisychain.gametest;

import com.daisychain.Daisychain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.EnchantmentTableBlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F39 (step 111): development game only (never in a real modpack). An enchanting table gets an inventory that a game
 * test can switch on and off without a block update, the way a Mekanism machine's Side Config does. Off by default, so
 * enchanting tables behave as in vanilla unless a test switches one on.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SwitchableInventory {
    private static final ResourceLocation ID = new ResourceLocation(Daisychain.MOD_ID, "switchable_inventory");
    private static final Map<BlockPos, LazyOptional<IItemHandler>> OPEN = new ConcurrentHashMap<>();
    private static final Map<BlockPos, ItemStackHandler> HANDLERS = new ConcurrentHashMap<>();

    private SwitchableInventory() {
    }

    /** Switches the table at pos (absolute) on or off. The caller then tells the neighbors, as Mekanism does. */
    public static void set(BlockPos pos, boolean open) {
        if (open) {
            ItemStackHandler handler = HANDLERS.computeIfAbsent(pos.immutable(), p -> new ItemStackHandler(1));
            OPEN.computeIfAbsent(pos.immutable(), p -> LazyOptional.of(() -> handler));
        } else {
            HANDLERS.remove(pos);
            LazyOptional<IItemHandler> old = OPEN.remove(pos);
            if (old != null) {
                old.invalidate();
            }
        }
    }

    /**
     * F41: the table offers this handler from now on, and the old one is NOT invalidated (what Functional Storage's
     * Controller Access Point does when it gets linked on 1.20.1).
     */
    public static void setQuietly(BlockPos pos, ItemStackHandler handler) {
        HANDLERS.put(pos.immutable(), handler);
        OPEN.put(pos.immutable(), LazyOptional.of(() -> handler));
    }

    /** Review R1/R7: the table at pos (absolute) offers exactly this inventory (see TestInventories). */
    public static void setHandler(BlockPos pos, IItemHandler handler) {
        HANDLERS.remove(pos);
        OPEN.put(pos.immutable(), LazyOptional.of(() -> handler));
    }

    /**
     * Review R8: how many listeners the table's handler holder has right now, or -1 if the table offers nothing.
     * Development game only: it reads a private field of Forge's LazyOptional.
     */
    public static int listenerCount(BlockPos pos) {
        LazyOptional<IItemHandler> holder = OPEN.get(pos);
        if (holder == null) {
            return -1;
        }
        try {
            java.lang.reflect.Field field = LazyOptional.class.getDeclaredField("listeners");
            field.setAccessible(true);
            return ((java.util.Set<?>) field.get(holder)).size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Can't read LazyOptional.listeners", e);
        }
    }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        if (FMLEnvironment.production || !(event.getObject() instanceof EnchantmentTableBlockEntity table)) {
            return;
        }
        event.addCapability(ID, new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                if (capability != ForgeCapabilities.ITEM_HANDLER) {
                    return LazyOptional.empty();
                }
                LazyOptional<IItemHandler> handler = OPEN.get(table.getBlockPos());
                return handler == null ? LazyOptional.empty() : handler.cast();
            }
        });
    }
}
