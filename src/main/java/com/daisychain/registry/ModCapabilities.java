package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.network.NoEnergy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;

import javax.annotation.Nullable;

/**
 * Capabilities our own blocks offer. Pipes offer only one, energy, on energy and universal pipes:
 * - Step 109: pull sides show an energy input (PipeDataBlockEntity.energyInput), so blocks that push energy can feed
 *   them.
 * - Step 141: every other side shows NoEnergy (takes and gives nothing), so blocks that only stay put on an energy
 *   block, like Powah's Energizing Rod, can sit on a pipe; the pipe then feeds them through its insert side.
 * Registered per block, not per block entity: bare pipes have no block entity, and the answer for a face must never
 * flicker while something sits on it (a rod drops off as soon as its base stops offering energy). No side (null)
 * gets nothing, so overlays that ask without a side don't show pipes as energy blocks.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID)
public final class ModCapabilities {
    private ModCapabilities() {
    }

    // Capability registration is a mod-bus event; FML 1.21.1 sends it to the mod bus automatically.
    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        Block[] energyPipes = ModBlocks.energyPipes();
        Block[] blocks = new Block[energyPipes.length + 1];
        System.arraycopy(energyPipes, 0, blocks, 0, energyPipes.length);
        blocks[energyPipes.length] = ModBlocks.UNIVERSAL_PIPE.get();
        event.registerBlock(Capabilities.EnergyStorage.BLOCK, ModCapabilities::pipeEnergy, blocks);
    }

    @Nullable
    private static IEnergyStorage pipeEnergy(Level level, BlockPos pos, BlockState state,
                                             @Nullable BlockEntity blockEntity, @Nullable Direction side) {
        if (side == null) {
            return null;
        }
        if (blockEntity instanceof PipeDataBlockEntity data) {
            IEnergyStorage input = data.energyInput(state, side); // pull sides only; the level's state, not the BE's
            if (input != null) {
                return input;
            }
        }
        return NoEnergy.INSTANCE;
    }
}
