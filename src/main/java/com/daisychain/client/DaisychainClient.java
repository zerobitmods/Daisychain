package com.daisychain.client;

import com.daisychain.Daisychain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only setup. Adds a Config button for Daisychain in the mod list (client hints; server settings in singleplayer). */
@Mod(value = Daisychain.MOD_ID, dist = Dist.CLIENT)
public final class DaisychainClient {
    public DaisychainClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
