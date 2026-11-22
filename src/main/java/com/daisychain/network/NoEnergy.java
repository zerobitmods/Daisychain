package com.daisychain.network;

import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * Step 141: the energy storage an energy or universal pipe shows on every side that isn't a pull side. It takes
 * nothing and gives nothing; it is there so blocks that only stay put on an energy block (Powah's Energizing Rod checks
 * its base face) can sit on a pipe. The pipe then feeds that block through its normal insert side. One shared
 * instance, so other mods asking or pushing into it cost no work and no allocation.
 */
public final class NoEnergy implements IEnergyStorage {
    public static final NoEnergy INSTANCE = new NoEnergy();

    private NoEnergy() {
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        return 0;
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
        return 0;
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return false;
    }
}
