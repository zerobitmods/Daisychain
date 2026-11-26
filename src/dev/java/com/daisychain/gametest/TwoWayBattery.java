package com.daisychain.gametest;

import com.daisychain.Daisychain;
import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Step F93 (port of 169, game tests only), energy blocks that answer "both ways" (no other dev hook uses these block
 * entities): a smoker is a half-full battery, a furnace a full battery, and a blast furnace a generator that says it
 * can take energy but refuses it (as Mekanism's generators do on their output face). Step F105: a brewing stand (flux point,
 * in the dev tag) and a campfire (unlinked flux plug) answer neither.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TwoWayBattery {
    private static final ResourceLocation ID = new ResourceLocation(Daisychain.MOD_ID, "two_way_battery");

    private TwoWayBattery() {
    }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        if (FMLEnvironment.production) {
            return;
        }
        BlockEntity be = event.getObject();
        IEnergyStorage storage;
        if (be instanceof SmokerBlockEntity) {
            storage = new EnergyStorage(100_000, 1_000, 1_000, 50_000);
        } else if (be instanceof BlastFurnaceBlockEntity) {
            storage = new RefusingGenerator();
        } else if (be instanceof FurnaceBlockEntity) {
            storage = new EnergyStorage(100_000, 1_000, 1_000, 100_000);
        } else if (be instanceof BrewingStandBlockEntity || be instanceof CampfireBlockEntity) {
            // Step F105 (port of 181): a brewing stand stands in for a Flux Networks flux point (in the dev copy of the
            // tag daisychain:energy_auto_pull), a campfire for a Flux Plug that hasn't joined its network yet (not in
            // it). Both say they neither give nor take energy.
            storage = new EnergyStorage(10_000, 0, 0, 0);
        } else {
            return;
        }
        LazyOptional<IEnergyStorage> holder = LazyOptional.of(() -> storage);
        event.addCapability(ID, new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                return capability == ForgeCapabilities.ENERGY ? holder.cast() : LazyOptional.empty();
            }
        });
        event.addListener(holder::invalidate);
    }

    private static final class RefusingGenerator implements IEnergyStorage {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return Math.min(maxExtract, 100);
        }

        @Override
        public int getEnergyStored() {
            return 0;
        }

        @Override
        public int getMaxEnergyStored() {
            return 10_000;
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }
}
