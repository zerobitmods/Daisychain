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
 * Step 85 (zerobit): Jade, server side. For the pull side you're looking at, sends its status (step 108, SideStatus) and,
 * on energy pipes, the route tiers its network speed comes from (worked out when the network was built, so this is a
 * few lookups). Only runs while someone looks at a pull side, about 4 times a second.
 */
public enum PipeNetworkDataProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    static final String BEST = "DaisychainBestRoute";
    static final String SLOWEST = "DaisychainSlowestRoute";
    static final String MACHINES = "DaisychainMachines";
    static final String STATUS = "DaisychainStatus";
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "pipe_network");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public boolean shouldRequestData(BlockAccessor accessor) {
        BlockState state = accessor.getBlockState();
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return false;
        }
        Direction looked = FlowPipeBlock.targetedSide(state, accessor.getPosition(), accessor.getHitResult());
        return FlowPipeBlock.isPullSide(accessor.getLevel(), accessor.getPosition(), state, looked);
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        BlockState state = accessor.getBlockState();
        if (!(accessor.getLevel() instanceof ServerLevel level) || !(state.getBlock() instanceof FlowPipeBlock)) {
            return;
        }
        BlockPos pos = accessor.getPosition();
        Direction looked = FlowPipeBlock.targetedSide(state, pos, accessor.getHitResult());
        if (!FlowPipeBlock.isPullSide(level, pos, state, looked)) {
            return;
        }
        NetworkManager manager = NetworkManager.get(level);
        data.putByte(STATUS, (byte) manager.sideStatus(pos, looked, -1));
        if (!(state.getBlock() instanceof EnergyPipeBlock)) {
            return;
        }
        int[] summary = manager.routeSummary(pos);
        if (summary == null) {
            return;
        }
        data.putByte(BEST, (byte) summary[0]);
        data.putByte(SLOWEST, (byte) summary[1]);
        data.putInt(MACHINES, summary[2]);
    }
}
