package com.daisychain.command;

import com.daisychain.Daisychain;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Step 120 (zerobit, 2026-11-13): hooks for the development-only code. Everything under src/dev (game tests, stress
 * tests, the test bench) is only in the development game (runClient, runGameTestServer, IntelliJ) and never in the
 * released jar, so the mod's own code can't name it; it attaches itself here instead. Brigadier merges this
 * "daisychain" node into the mod's own, so the commands stay /daisychain stress ... and /daisychain testbench.
 */
@EventBusSubscriber(modid = Daisychain.MOD_ID)
public final class DevCommands {
    private DevCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("daisychain")
                .then(StressTest.command().requires(source -> source.hasPermission(2)))
                .then(TestBench.command().requires(source -> source.hasPermission(2)))); // step 96
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        StressMeasure.clear(); // step 115
    }
}
