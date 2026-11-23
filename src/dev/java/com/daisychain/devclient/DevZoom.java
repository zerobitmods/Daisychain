package com.daisychain.devclient;

import com.daisychain.Daisychain;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Development game only. Hold C to zoom in (field of view / 4); scroll while holding
 * to zoom further in or out. Lets the pipe looks be checked up close without flying into them.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class DevZoom {

    public static final KeyMapping ZOOM = new KeyMapping("Zoom (dev)", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, "key.categories.misc");

    private static double factor = 4.0;

    private DevZoom() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(ZOOM);
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (ZOOM.isDown()) {
            event.setFOV(event.getFOV() / factor);
        } else {
            factor = 4.0;
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (!ZOOM.isDown()) {
            return;
        }
        factor = Math.max(1.5, Math.min(30.0, factor * (event.getScrollDeltaY() > 0 ? 1.25 : 0.8)));
        event.setCanceled(true); // don't change the hotbar slot while zooming
    }
}
