package com.daisychain.compat.jade;

import com.daisychain.Daisychain;
import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.config.UpgradeSpeeds;
import com.daisychain.item.UpgradeItem;
import com.daisychain.registry.ModItems;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade: the upgrade and top speed of the pull side you're looking at. Client only; everything shown is already synced
 * to the client (side settings, upgrades and the server's speed config).
 */
public enum PipeInfoProvider implements IBlockComponentProvider {
    INSTANCE;

    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "pipe");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        BlockState state = accessor.getBlockState();
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return;
        }
        Level level = accessor.getLevel();
        BlockPos pos = accessor.getPosition();
        Direction looked = FlowPipeBlock.targetedSide(state, pos, accessor.getHitResult());
        if (!FlowPipeBlock.isPullSide(level, pos, state, looked)) {
            return; // only pull sides move things, so only they have an upgrade and a speed
        }
        if (state.getBlock() instanceof EnergyPipeBlock energy) {
            // Step 84: energy pipes have no upgrades; their speed comes from their tier. Step 85: and from the routes
            // on their network, which only the server knows (PipeNetworkDataProvider).
            if (UpgradeSpeeds.available()) {
                CompoundTag server = accessor.getServerData();
                if (server.contains(PipeNetworkDataProvider.BEST)) {
                    int machines = server.getInt(PipeNetworkDataProvider.MACHINES);
                    if (machines == 0) {
                        tooltip.add(Component.translatable("jade.daisychain.no_machines").withStyle(ChatFormatting.GRAY));
                        return;
                    }
                    int best = server.getByte(PipeNetworkDataProvider.BEST);
                    int slowest = server.getByte(PipeNetworkDataProvider.SLOWEST);
                    tooltip.add(Component.translatable("jade.daisychain.network_speed", energySpeed(best))
                            .withStyle(ChatFormatting.GRAY));
                    if (slowest < best) {
                        tooltip.add(Component.translatable("jade.daisychain.slowest_route", energySpeed(slowest))
                                .withStyle(ChatFormatting.GRAY));
                    }
                } else {
                    tooltip.add(Component.translatable("jade.daisychain.pipe_speed", energySpeed(energy.tier()))
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            return;
        }
        PipeDataBlockEntity data = FlowPipeBlock.getData(level, pos, state);
        int upgrade = data == null ? 0 : data.upgradeLevel(looked);
        tooltip.add(upgradeAndSpeed(FlowPipeBlock.kindOf(state), upgrade).withStyle(ChatFormatting.GRAY));
    }

    private static Component upgradeName(int level) {
        UpgradeItem item = ModItems.upgradeFor(level);
        return item == null ? Component.translatable("jade.daisychain.no_upgrade") : item.getDescription();
    }

    /** "Gold Upgrade · up to 64 items/s" (universal pipes list all three resources). */
    private static MutableComponent upgradeAndSpeed(PipeKind kind, int level) {
        MutableComponent line = Component.empty().append(upgradeName(level));
        if (!UpgradeSpeeds.available()) {
            return line;
        }
        MutableComponent speeds = Component.empty();
        boolean first = true;
        if (kind.carries(PipeKind.ITEMS)) {
            long perSecond = Math.round(UpgradeSpeeds.items(level) * 20.0 / UpgradeSpeeds.interval(level));
            speeds.append(Component.translatable("jade.daisychain.speed.items", number(perSecond)));
            first = false;
        }
        if (kind.carries(PipeKind.FLUIDS)) {
            long perSecond = Math.round(UpgradeSpeeds.fluidMb(level) * 20.0 / UpgradeSpeeds.fluidInterval(level));
            if (!first) {
                speeds.append(", ");
            }
            speeds.append(Component.translatable("jade.daisychain.speed.fluids", number(perSecond)));
            first = false;
        }
        if (kind.carries(PipeKind.ENERGY_RES)) {
            long perTick = UpgradeSpeeds.energyFe(level) / Math.max(1, UpgradeSpeeds.energyInterval(level));
            if (!first) {
                speeds.append(", ");
            }
            speeds.append(Component.translatable("jade.daisychain.speed.energy", number(perTick)));
        }
        return line.append(" · ").append(Component.translatable("jade.daisychain.up_to", speeds));
    }

    /** "100,000 FE/t" for an energy tier (UpgradeSpeeds levels). */
    private static Component energySpeed(int tier) {
        int level = Math.max(0, Math.min(5, tier));
        long perTick = UpgradeSpeeds.energyFe(level) / Math.max(1, UpgradeSpeeds.energyInterval(level));
        return Component.translatable("jade.daisychain.speed.energy", number(perTick));
    }

    private static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
