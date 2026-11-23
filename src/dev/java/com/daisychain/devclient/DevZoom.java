package com.daisychain.devclient;

import com.daisychain.Daisychain;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Step F72 (port of 146): development game only. Hold C to zoom in (field of view / 4); scroll while holding to zoom
 * further in or out. Lets the pipe looks be checked up close without flying into them.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DevZoom {

    public static final KeyMapping ZOOM = new KeyMapping("Zoom (dev)", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, "key.categories.misc");

    private static double factor = 4.0;

    private DevZoom() {
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
        factor = Math.max(1.5, Math.min(30.0, factor * (event.getScrollDelta() > 0 ? 1.25 : 0.8)));
        event.setCanceled(true); // don't change the hotbar slot while zooming
    }

    /** Key mappings register on the mod bus in 1.20.1. */
    @Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        private Keys() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(ZOOM);
        }
    }
}
