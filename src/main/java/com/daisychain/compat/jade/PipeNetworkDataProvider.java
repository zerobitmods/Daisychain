package com.daisychain.compat.jade;

import com.daisychain.Daisychain;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.network.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * Step 85 (zerobit): Jade, server side (registered for the pipe block entity: pull sides always have one). For the energy pipe pull side you're looking at, sends the route tiers its
 * network speed comes from (worked out when the network was built, so this is a few lookups). Only runs while someone
 * looks at an energy pull side, about 4 times a second.
 */
public enum PipeNetworkDataProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    static final String BEST = "DaisychainBestRoute";
    static final String SLOWEST = "DaisychainSlowestRoute";
    static final String MACHINES = "DaisychainMachines";
    private static final ResourceLocation UID = new ResourceLocation(Daisychain.MOD_ID, "pipe_network");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level)
                || !(accessor.getBlockState().getBlock() instanceof EnergyPipeBlock)) {
            return;
        }
        BlockPos pos = accessor.getPosition();
        // Jade 11 (1.20.1) asks every provider while you look at a pipe with settings: only pull sides need this.
        Direction looked = FlowPipeBlock.targetedSide(accessor.getBlockState(), pos, accessor.getHitResult());
        if (!FlowPipeBlock.isPullSide(level, pos, accessor.getBlockState(), looked)) {
            return;
        }
        int[] summary = NetworkManager.get(level).routeSummary(pos);
        if (summary == null) {
            return;
        }
        data.putByte(BEST, (byte) summary[0]);
        data.putByte(SLOWEST, (byte) summary[1]);
        data.putInt(MACHINES, summary[2]);
    }
}
