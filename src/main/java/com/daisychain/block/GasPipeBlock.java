package com.daisychain.block;

import com.daisychain.compat.Chemicals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Step F95 (port of step 171): gas pipe. Carries Mekanism chemicals (gas, infusion, pigment, slurry). Connects to other
 * gas pipes and to any block that offers any of them on that side. Without Mekanism (or after its hookup broke) it
 * connects only to other gas pipes and carries nothing.
 */
public final class GasPipeBlock extends BasePipeBlock {

    public GasPipeBlock(Properties properties) {
        super(properties);
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
