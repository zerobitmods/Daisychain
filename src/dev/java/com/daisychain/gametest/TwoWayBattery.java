package com.daisychain.gametest;

import com.daisychain.Daisychain;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Step 169 (game tests only), energy blocks that answer "both ways" (no other dev hook uses these block entities):
 * a smoker is a half-full battery, a furnace a full battery, and a blast furnace a generator that says it can take
 * energy but refuses it (as Mekanism's generators do on their output face).
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID)
public final class TwoWayBattery {
    private TwoWayBattery() {
    }

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BlockEntityType.SMOKER,
                (blockEntity, side) -> new EnergyStorage(100_000, 1_000, 1_000, 50_000));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BlockEntityType.FURNACE,
                (blockEntity, side) -> new EnergyStorage(100_000, 1_000, 1_000, 100_000));
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BlockEntityType.BLAST_FURNACE,
                (blockEntity, side) -> new IEnergyStorage() {
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
                });
    }
}
