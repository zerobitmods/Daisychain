package com.daisychain.client;

import com.daisychain.Daisychain;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Step 48f/48h: "Undo last paste" (default Z) and "Redo last undo" (default R) keys, changeable in Controls under
 * Daisychain. They only do something while the player holds the Settings Copier (see PasteHighlights.onClientTick).
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CopierKeys {

    public static final KeyMapping UNDO_PASTE = new KeyMapping("key.daisychain.undo_paste", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.daisychain");

    public static final KeyMapping REDO_PASTE = new KeyMapping("key.daisychain.redo_paste", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.daisychain");


    private CopierKeys() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(UNDO_PASTE);
        event.register(REDO_PASTE);
    }
}
