package com.daisychain.client;

import com.daisychain.Daisychain;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderTooltipEvent;

/**
 * Step 47b: tooltips on Daisychain's side settings screen use the spec's colors (tooltip background #08090B, border
 * #3A3D46) instead of vanilla's purple frame, so they match the flat panels. Other screens keep vanilla tooltips.
 * Uses NeoForge's RenderTooltipEvent.Color (1.21.1). Client only.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
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
