package com.daisychain.gametest;

import com.daisychain.Daisychain;
import net.minecraft.core.Direction;
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
 * Step 141 (game tests only): a jukebox acts like a block that takes energy through one face only, its bottom,
 * the way Powah's Energizing Rod takes it only through its base. Every other face offers nothing. (Jukebox because no
 * other dev hook uses it; daylight detectors are the stress tests' energy sources.) Takes at most MAX_RECEIVE per call,
 * never fills up, and counts what it received.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID)
public final class OneFaceSink implements IEnergyStorage {
    static final Direction FACE = Direction.DOWN;
    static final int MAX_RECEIVE = 1_000;
    private static final Map<BlockEntity, OneFaceSink> SINKS = new WeakHashMap<>();

    long received;

    private OneFaceSink() {
    }

    static OneFaceSink of(BlockEntity blockEntity) {
        return SINKS.computeIfAbsent(blockEntity, be -> new OneFaceSink());
    }

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BlockEntityType.JUKEBOX,
                (blockEntity, side) -> side == FACE ? of(blockEntity) : null);
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        int n = Math.max(0, Math.min(maxReceive, MAX_RECEIVE));
        if (!simulate) {
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
        return 0;
    }

    @Override
    public int getMaxEnergyStored() {
        return Integer.MAX_VALUE;
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
