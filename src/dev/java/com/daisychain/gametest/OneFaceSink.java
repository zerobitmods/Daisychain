package com.daisychain.gametest;

import com.daisychain.Daisychain;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Step F68 (port of 141, game tests only): a jukebox acts like a block that takes energy through one face only, its
 * bottom, the way Powah's Energizing Rod takes it only through its base. Every other face offers nothing. (Jukebox
 * because no other dev hook uses it; daylight detectors are the stress tests' energy sources.) Takes at most
 * MAX_RECEIVE per call, never fills up, and counts what it received.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class OneFaceSink implements IEnergyStorage {
    static final Direction FACE = Direction.DOWN;
    static final int MAX_RECEIVE = 1_000;
    private static final ResourceLocation ID = new ResourceLocation(Daisychain.MOD_ID, "one_face_sink");
    private static final Map<BlockEntity, OneFaceSink> SINKS = new WeakHashMap<>();

    long received;

    private OneFaceSink() {
    }

    static synchronized OneFaceSink of(BlockEntity blockEntity) {
        return SINKS.computeIfAbsent(blockEntity, be -> new OneFaceSink());
    }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        if (FMLEnvironment.production || !(event.getObject() instanceof JukeboxBlockEntity detector)) {
            return;
        }
        LazyOptional<IEnergyStorage> holder = LazyOptional.of(() -> of(detector));
        event.addCapability(ID, new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                return capability == ForgeCapabilities.ENERGY && side == FACE ? holder.cast() : LazyOptional.empty();
            }
        });
        event.addListener(holder::invalidate);
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
