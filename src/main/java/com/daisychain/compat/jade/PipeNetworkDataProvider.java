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
 * Step 85 (zerobit): Jade, server side (registered for the pipe block entity: pull sides always have one). For the
 * pull side you're looking at, sends its status (step 108, SideStatus) and, on energy pipes, the route tiers its
 * network speed comes from (worked out when the network was built, so this is a few lookups). Only runs while someone
 * looks at a pull side, about 4 times a second.
 */
public enum PipeNetworkDataProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    static final String BEST = "DaisychainBestRoute";
    static final String SLOWEST = "DaisychainSlowestRoute";
    static final String MACHINES = "DaisychainMachines";
    static final String STATUS = "DaisychainStatus";
    private static final ResourceLocation UID = new ResourceLocation(Daisychain.MOD_ID, "pipe_network");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        BlockState state = accessor.getBlockState();
        if (!(accessor.getLevel() instanceof ServerLevel level) || !(state.getBlock() instanceof FlowPipeBlock)) {
            return;
        }
        BlockPos pos = accessor.getPosition();
        // Jade 11 (1.20.1) asks every provider while you look at a pipe with settings: only pull sides need this.
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
