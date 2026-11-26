package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.compat.Chemicals;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Step 172 (game tests only): with Mekanism loaded, barrels get a chemical handler that throws on a real insert
 * (MekanismTestKit). This class names no Mekanism type, so it loads fine without Mekanism.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID)
public final class GasTestCapabilities {
    private GasTestCapabilities() {
    }

    @SubscribeEvent
    public static void register(RegisterCapabilitiesEvent event) {
        if (Chemicals.mekanismLoaded()) {
            MekanismTestKit.registerThrowingBarrel(event);
        }
    }
}
