package com.daisychain.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * Universal pipe (step 33): carries items, fluids and energy at once. Connects to other universal pipes and to any
 * block that exposes an item handler, a fluid handler or an energy storage.
 */
public final class UniversalPipeBlock extends FlowPipeBlock {

    public static final MapCodec<UniversalPipeBlock> CODEC = simpleCodec(UniversalPipeBlock::new);

    public UniversalPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<UniversalPipeBlock> codec() {
        return CODEC;
    }

    @Override
    public PipeKind kind() {
        return PipeKind.UNIVERSAL;
    }

    @Override
    protected boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide) {
        return level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, targetSide) != null
                || level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, targetSide) != null
                || level.getCapability(Capabilities.EnergyStorage.BLOCK, targetPos, targetSide) != null;
    }
}
