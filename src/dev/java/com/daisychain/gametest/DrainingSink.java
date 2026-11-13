package com.daisychain.gametest;

import com.daisychain.Daisychain;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Step 121 (game tests only): a bell acts as a machine that uses energy every tick, like a laser drill. It holds
 * CAPACITY FE, takes at most MAX_RECEIVE per call, and burns DRAIN FE per tick while it has any. Draining is worked out
 * from the game time whenever it is asked, so it needs no ticking. Counts what it received and the FE it went short.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID)
public final class DrainingSink implements IEnergyStorage {
    static final int CAPACITY = 4_000;
    static final int MAX_RECEIVE = 4_000;
    static final int DRAIN = 1_000;
    private static final Map<BlockEntity, DrainingSink> SINKS = new WeakHashMap<>();

    private final BlockEntity owner;
    private int stored;
    private long lastTime = Long.MIN_VALUE;
    long received;
    long shortFe;

    private DrainingSink(BlockEntity owner) {
        this.owner = owner;
    }

    static DrainingSink of(BlockEntity blockEntity) {
        return SINKS.computeIfAbsent(blockEntity, DrainingSink::new);
    }

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BlockEntityType.BELL,
                (blockEntity, side) -> of(blockEntity));
    }

    /** Burns DRAIN per tick since the last look (the current tick's use happens before anything arrives in it). */
    void settle() {
        long now = owner.getLevel() != null ? owner.getLevel().getGameTime() : 0;
        if (lastTime == Long.MIN_VALUE) {
            lastTime = now;
            return;
        }
        long ticks = now - lastTime;
        if (ticks <= 0) {
            return;
        }
        lastTime = now;
        long need = ticks * DRAIN;
        if (stored >= need) {
            stored -= (int) need;
        } else {
            shortFe += need - stored;
            stored = 0;
        }
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        settle();
        int n = Math.max(0, Math.min(Math.min(maxReceive, MAX_RECEIVE), CAPACITY - stored));
        if (!simulate) {
            stored += n;
            received += n;
        }
        return n;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        settle();
        return stored;
    }

    @Override
    public int getMaxEnergyStored() {
        return CAPACITY;
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return true;
    }
}
