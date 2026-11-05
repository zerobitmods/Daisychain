package com.daisychain.command;

import com.daisychain.Daisychain;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DaylightDetectorBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

/**
 * Step 87 (zerobit): test-only energy blocks for /daisychain stress energy. Vanilla has no batteries, so in the
 * development game only (never in a real modpack) two vanilla blocks get an energy storage: a daylight detector is an
 * endless source (gives any amount, takes none) and a lectern an endless sink (takes any amount, holds none; step 100: it counts what arrives). That
 * makes every energy pipe move energy on every tick, the busiest case.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StressTestEnergy {
    /** True in the development game (./gradlew runClient), where the test blocks exist. */
    public static final boolean AVAILABLE = !FMLEnvironment.production;
    private static final ResourceLocation ID = new ResourceLocation(Daisychain.MOD_ID, "stress_test_energy");

    static final IEnergyStorage SOURCE = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return Math.max(0, maxExtract);
        }

        @Override
        public int getEnergyStored() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int getMaxEnergyStored() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    };

    /**
     * Step 100: one counting sink per lectern. It still takes any amount and keeps none of it, but it remembers how much
     * arrived: Jade (and anything else reading "stored energy") shows the FE it received during the last game tick, so
     * the number is the flow rate in FE/t; the game tests read the running total.
     */
    private static final Map<BlockEntity, CountingSink> SINKS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Step 100: total FE this lectern has taken in, for the game tests. 0 if it never got any. */
    public static long received(BlockEntity lectern) {
        CountingSink sink = SINKS.get(lectern);
        return sink == null ? 0 : sink.total;
    }

    private static CountingSink sinkFor(BlockEntity lectern) {
        return SINKS.computeIfAbsent(lectern, CountingSink::new);
    }

    static final class CountingSink implements IEnergyStorage {
        private final BlockEntity owner;
        private long total;
        /** Game time the counter below belongs to, the FE received in that tick, and in the tick before it. */
        private long tick = Long.MIN_VALUE;
        private long thisTick;
        private long lastTick;

        CountingSink(BlockEntity owner) {
            this.owner = owner;
        }

        private void roll() {
            long now = owner.getLevel() == null ? 0 : owner.getLevel().getGameTime();
            if (now != tick) {
                lastTick = now == tick + 1 ? thisTick : 0;
                thisTick = 0;
                tick = now;
            }
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int amount = Math.max(0, maxReceive);
            if (!simulate && amount > 0) {
                roll();
                thisTick += amount;
                total += amount;
            }
            return amount;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        /** The FE received during the last full tick (the flow rate), so Jade's number moves while power flows. */
        @Override
        public int getEnergyStored() {
            roll();
            return (int) Math.min(Integer.MAX_VALUE, lastTick);
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

    private static final LazyOptional<IEnergyStorage> SOURCE_CAP = LazyOptional.of(() -> SOURCE);

    private StressTestEnergy() {
    }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        if (!AVAILABLE) {
            return;
        }
        BlockEntity blockEntity = event.getObject();
        LazyOptional<IEnergyStorage> storage = blockEntity instanceof DaylightDetectorBlockEntity ? SOURCE_CAP
                : blockEntity instanceof LecternBlockEntity ? LazyOptional.of(() -> sinkFor(blockEntity)) : null;
        if (storage == null) {
            return;
        }
        event.addCapability(ID, new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                return capability == ForgeCapabilities.ENERGY ? storage.cast() : LazyOptional.empty();
            }
        });
    }
}
