package com.daisychain.block;

import com.daisychain.compat.Chemicals;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Step 171: gas pipe. Carries Mekanism chemicals (gas, infusion, pigment, slurry). Connects to other gas pipes and to
 * any block that offers chemicals on that side. Without Mekanism (or after its hookup broke) it connects only to other
 * gas pipes and carries nothing.
 */
public final class GasPipeBlock extends BasePipeBlock {

    public static final MapCodec<GasPipeBlock> CODEC = simpleCodec(GasPipeBlock::new);

    public GasPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<GasPipeBlock> codec() {
        return CODEC;
    }

    @Override
    public PipeKind kind() {
        return PipeKind.GAS;
    }

    @Override
    protected boolean canConnectToBlock(Level level, BlockPos targetPos, Direction targetSide) {
        return Chemicals.hasHandler(level, targetPos, targetSide);
    }
}
