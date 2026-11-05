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
 * Step 85 (zerobit): Jade, server side. For the energy pipe pull side you're looking at, sends the route tiers its
 * network speed comes from (worked out when the network was built, so this is a few lookups). Only runs while someone
 * looks at an energy pull side, about 4 times a second.
 */
public enum PipeNetworkDataProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    static final String BEST = "DaisychainBestRoute";
    static final String SLOWEST = "DaisychainSlowestRoute";
    static final String MACHINES = "DaisychainMachines";
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "pipe_network");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public boolean shouldRequestData(BlockAccessor accessor) {
        BlockState state = accessor.getBlockState();
        if (!(state.getBlock() instanceof EnergyPipeBlock)) {
            return false;
        }
        Direction looked = FlowPipeBlock.targetedSide(state, accessor.getPosition(), accessor.getHitResult());
        return FlowPipeBlock.isPullSide(accessor.getLevel(), accessor.getPosition(), state, looked);
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level)
                || !(accessor.getBlockState().getBlock() instanceof EnergyPipeBlock)) {
            return;
        }
        BlockPos pos = accessor.getPosition();
        int[] summary = NetworkManager.get(level).routeSummary(pos);
        if (summary == null) {
            return;
        }
        data.putByte(BEST, (byte) summary[0]);
        data.putByte(SLOWEST, (byte) summary[1]);
        data.putInt(MACHINES, summary[2]);
    }
}
