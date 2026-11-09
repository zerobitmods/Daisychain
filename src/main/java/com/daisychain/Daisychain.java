package com.daisychain;

import com.daisychain.config.DaisychainClientConfig;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.net.DaisychainNetwork;
import com.daisychain.registry.ModBlockEntities;
import com.daisychain.registry.ModBlocks;
import com.daisychain.registry.ModCreativeTabs;
import com.daisychain.registry.ModItems;
import com.daisychain.registry.ModMenus;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Daisychain.MOD_ID)
public final class Daisychain {
    public static final String MOD_ID = "daisychain";
    public static final Logger LOGGER = LogUtils.getLogger();
    /** Development step this build is at. Shown by /daisychain version; bumped with every step. */
    public static final String DEV_STEP = "F39";

    public Daisychain() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModMenus.register(modEventBus);
        DaisychainEvents.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, DaisychainConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, DaisychainClientConfig.SPEC);
        modEventBus.addListener(this::commonSetup);
        // Step 98 / F26: optional FTB Ultimine add-on, only with an Ultimine new enough to have its right-click API.
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
        event.enqueueWork(DaisychainNetwork::register);
        LOGGER.info("Daisychain loaded");
    }
}
