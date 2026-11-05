package com.daisychain.client;

import com.daisychain.Daisychain;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/**
 * Step 47b: tooltips on Daisychain's side settings screen use the spec's colors (tooltip background #08090B, border
 * #3A3D46) instead of vanilla's purple frame, so they match the flat panels. Other screens keep vanilla tooltips.
 * Uses NeoForge's RenderTooltipEvent.Color (1.21.1). Client only.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class FlowTooltips {
    private static final int BACKGROUND = 0xF008090B;
    private static final int BORDER = 0xFF3A3D46;

    private FlowTooltips() {
    }

    @SubscribeEvent
    public static void onTooltipColor(RenderTooltipEvent.Color event) {
        if (Minecraft.getInstance().screen instanceof PullSideScreen) {
            event.setBackground(BACKGROUND);
            event.setBorderStart(BORDER);
            event.setBorderEnd(BORDER);
        }
    }
}
