package com.daisychain.devclient;

import com.daisychain.Daisychain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Step 140 (from Forge F66): development game only. With -Pdev_shot=<name> (and -Pquick_play=<world folder>, see build.gradle), once
 * the world is open: put a wrench in the player's hand (unless the name contains "bare"), take two screenshots of the
 * "Step F66 check" rows (rows 1-2, rows 3-4; GUI hidden) into run/screenshots/<name>-rows12.png / -rows34.png, then
 * an F3 screenshot (<name>-f3.png), then close the game. Lets the facade see-through view be checked without a person at the keyboard. Does nothing unless
 * the system property daisychain.devShot is set.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class DevShot {
    private static final String NAME = System.getProperty("daisychain.devShot");
    private static int ticks = -1;

    private DevShot() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (NAME == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.options.pauseOnLostFocus = false; // the window may not have focus; not saved to options.txt
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (minecraft.screen != null) {
            minecraft.setScreen(null); // a pause screen from before the line above took effect
            return;
        }
        ticks++;
        // The wrench comes 10 s after the world opens: renderer mods (Sodium) skip rebuild requests for sections whose
        // first mesh isn't finished, so an earlier wrench left some rows solid at random. Players don't hold one that soon.
        switch (ticks) {
            case 20 -> {
                minecraft.options.hideGui = true;
                run(minecraft, "time set noon");
                run(minecraft, "tp @p -7 -56 0 facing -7 -59 8");
            }
            case 200 -> run(minecraft, NAME.contains("bare") ? "item replace entity @p weapon.mainhand with air"
                    : "item replace entity @p weapon.mainhand with daisychain:bloom_wrench");
            case 300 -> Screenshot.grab(minecraft.gameDirectory, NAME + "-rows12.png", minecraft.getMainRenderTarget(),
                    message -> { });
            case 310 -> run(minecraft, "tp @p 8 -56 0 facing 8 -59 8");
            case 410 -> Screenshot.grab(minecraft.gameDirectory, NAME + "-rows34.png", minecraft.getMainRenderTarget(),
                    message -> { });
            case 420 -> {
                minecraft.options.hideGui = false; // F3 screen: shows which renderer is active (Sodium adds lines)
                minecraft.getDebugOverlay().toggleOverlay();
            }
            case 440 -> Screenshot.grab(minecraft.gameDirectory, NAME + "-f3.png", minecraft.getMainRenderTarget(),
                    message -> { });
            case 460 -> minecraft.stop();
            default -> {
            }
        }
    }

    private static void run(Minecraft minecraft, String command) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
        }
    }
}
