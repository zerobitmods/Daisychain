package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.registry.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Tells the client which screen to show for each of Daisychain's menus. Client only. */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientScreens {
    private ClientScreens() {
    }

    /**
     * Step 147 / F73: the mod list's Config button opens the Pipe colours screen (Forge 1.20.1 has no built-in settings
     * screen, so there's no hub as on NeoForge; the other client settings stay in daisychain-client.toml). Called from
     * the mod constructor on the client only.
     */
    public static void registerConfigButton() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new PipeColourScreen(parent)));
    }

    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        // MenuScreens.register is not thread-safe, so it runs on the main thread.
        event.enqueueWork(() -> MenuScreens.register(ModMenus.PULL_SIDE.get(), PullSideScreen::new));
    }
}
