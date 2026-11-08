package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.daisychain.block.PipeDataBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Step 109: capabilities our own blocks offer. Pipes offer only one: an energy input on pull sides of energy and
 * universal pipes (PipeDataBlockEntity.energyInput), so blocks that push energy can feed them.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID)
public final class ModCapabilities {
    private ModCapabilities() {
    }

    // Capability registration is a mod-bus event; FML 1.21.1 sends it to the mod bus automatically.
    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.PIPE_DATA.get(),
                PipeDataBlockEntity::energyInput);
    }
}
