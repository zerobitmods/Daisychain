package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Tells the client which screen to show for each of Daisychain's menus. Client only. */
// Registration events are mod-bus events; FML 1.21.1 sends them to the mod bus automatically.
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class ClientScreens {
    private ClientScreens() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.PULL_SIDE.get(), PullSideScreen::new);
    }
}
