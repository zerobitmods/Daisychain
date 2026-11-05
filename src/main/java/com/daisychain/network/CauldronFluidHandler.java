package com.daisychain.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

/**
 * Lets pipes fill and empty vanilla cauldrons on 1.20.1, like NeoForge 1.21.1 does. A cauldron holds whole buckets
 * only, so this moves exactly 1000 mB at a time: water or lava into an empty cauldron, and out of a full water cauldron
 * or a lava cauldron. Smaller offers are refused, so no fluid is ever rounded away or made up.
 */
public final class CauldronFluidHandler implements IFluidHandler {
    private static final int BUCKET = 1000;

    private final Level level;
    private final BlockPos pos;

    public CauldronFluidHandler(Level level, BlockPos pos) {
        this.level = level;
        this.pos = pos.immutable();
    }

    /** True if the block is one this handler understands (any vanilla cauldron). */
    public static boolean isCauldron(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.CAULDRON || block == Blocks.WATER_CAULDRON || block == Blocks.LAVA_CAULDRON;
    }

    /** True while the position still holds a cauldron (checked before every use). */
    boolean stillThere() {
        return level.isLoaded(pos) && isCauldron(level.getBlockState(pos));
    }

    /** The full bucket this cauldron holds, or EMPTY (empty, part-full water, or not a cauldron any more). */
    private FluidStack contents() {
        if (!level.isLoaded(pos)) {
            return FluidStack.EMPTY;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() == Blocks.LAVA_CAULDRON) {
            return new FluidStack(Fluids.LAVA, BUCKET);
        }
        if (state.getBlock() == Blocks.WATER_CAULDRON
                && state.getValue(LayeredCauldronBlock.LEVEL) == LayeredCauldronBlock.MAX_FILL_LEVEL) {
            return new FluidStack(Fluids.WATER, BUCKET);
        }
        return FluidStack.EMPTY;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return tank == 0 ? contents() : FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
        return tank == 0 ? BUCKET : 0;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return tank == 0 && !stack.hasTag() && (stack.getFluid() == Fluids.WATER || stack.getFluid() == Fluids.LAVA);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || resource.getAmount() < BUCKET || !isFluidValid(0, resource) || !level.isLoaded(pos)) {
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() != Blocks.CAULDRON) {
            return 0; // only an empty cauldron takes a bucket
        }
        if (action.execute()) {
            Fluid fluid = resource.getFluid();
            BlockState filled = fluid == Fluids.LAVA ? Blocks.LAVA_CAULDRON.defaultBlockState()
                    : Blocks.WATER_CAULDRON.defaultBlockState()
                            .setValue(LayeredCauldronBlock.LEVEL, LayeredCauldronBlock.MAX_FILL_LEVEL);
            level.setBlockAndUpdate(pos, filled);
            level.gameEvent(GameEvent.FLUID_PLACE, pos, GameEvent.Context.of(filled));
        }
        return BUCKET;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        FluidStack inside = contents();
        if (inside.isEmpty() || resource.isEmpty() || !inside.isFluidEqual(resource)) {
            return FluidStack.EMPTY;
        }
        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        FluidStack inside = contents();
        if (inside.isEmpty() || maxDrain < BUCKET) {
            return FluidStack.EMPTY;
        }
        if (action.execute()) {
            BlockState empty = Blocks.CAULDRON.defaultBlockState();
            level.setBlockAndUpdate(pos, empty);
            level.gameEvent(GameEvent.FLUID_PICKUP, pos, GameEvent.Context.of(empty));
        }
        return inside;
    }
}
