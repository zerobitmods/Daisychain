package com.daisychain.network;

import com.daisychain.block.PipeDataBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * Step 109: the energy storage a pull side of an energy or universal pipe shows to the block next to it, so blocks that
 * only push energy (a Flux Point, cables, generators that push) can feed the pipe. It holds nothing: everything pushed
 * in goes straight on through NetworkManager.receivePushedEnergy, and nothing can be taken out (pipes never give
 * energy back to the block they pull from). One per pull side, made when another block first asks for it.
 */
public final class EnergyInput implements IEnergyStorage {
    private final PipeDataBlockEntity pipe;
    private final Direction side;

    public EnergyInput(PipeDataBlockEntity pipe, Direction side) {
        this.pipe = pipe;
        this.side = side;
    }

    private NetworkManager manager() {
        return !pipe.isRemoved() && pipe.getLevel() instanceof ServerLevel level ? NetworkManager.get(level) : null;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        NetworkManager manager = manager();
        return manager == null ? 0 : manager.receivePushedEnergy(pipe.getBlockPos(), side, maxReceive, simulate);
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
        NetworkManager manager = manager();
        return manager == null ? 0 : manager.pushedEnergyCapacity(pipe.getBlockPos(), side);
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        NetworkManager manager = manager();
        return manager != null && manager.acceptsPushedEnergy(pipe.getBlockPos(), side);
    }
}
