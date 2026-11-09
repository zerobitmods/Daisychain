package com.daisychain;

import com.daisychain.config.DaisychainClientConfig;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.net.DaisychainNetwork;
import com.daisychain.registry.ModBlockEntities;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModCreativeTabs;
import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModItems;
import com.daisychain.registry.ModMenus;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

@Mod(Daisychain.MOD_ID)
public final class Daisychain {
    public static final String MOD_ID = "daisychain";
    public static final Logger LOGGER = LogUtils.getLogger();
    /** Development step this build is at. Shown by /daisychain version; bumped with every step. */
    public static final String DEV_STEP = "111";

    public Daisychain(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModMenus.register(modEventBus);
        ModDataComponents.register(modEventBus);
        DaisychainEvents.register();
        modContainer.registerConfig(ModConfig.Type.SERVER, DaisychainConfig.SERVER_SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, DaisychainClientConfig.SPEC);
        modEventBus.addListener(DaisychainNetwork::register);
        modEventBus.addListener(this::commonSetup);
        // Step 98: optional FTB Ultimine add-on, only with an Ultimine new enough to have its right-click API.
        if (ModList.get().isLoaded("ftbultimine") && classExists("dev.ftb.mods.ftbultimine.api.rightclick.RegisterRightClickHandlerEvent")) {
            com.daisychain.compat.ultimine.UltimineCompat.register();
            LOGGER.info("Daisychain: FTB Ultimine found; Ultimine + right-click swaps energy pipe tiers");
        }
    }

    /** True if the class can be found, without loading it. */
    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, Daisychain.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Daisychain loaded");
    }
}
