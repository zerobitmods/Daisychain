package com.daisychain.command;

import com.daisychain.network.NetworkManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * /daisychain stress measure (step 115, development game only): the mod-page measurement in one command. Waits 10 s
 * (the build settles), then takes three 30-second readings of what pipes cost in this dimension, prints each and their
 * average, and then starts spark for 60 s if it's installed. Uses the same numbers as /daisychain stats (and restarts
 * its "since the last stats" window). One measurement at a time; running it again starts over.
 */
public final class StressMeasure {
    private static final int SETTLE_TICKS = 200;
    private static final int READING_TICKS = 600;
    private static final int READINGS = 3;
    private static final String SPARK_COMMAND = "spark profiler --timeout 60";

    private static boolean listening;
    @Nullable
    private static Run run;

    private static final class Run {
        final CommandSourceStack source;
        final ServerLevel level;
        int ticks;
        int readings;
        double sumMs;
        double minMs = Double.MAX_VALUE;
        double maxMs;
        double busiestMs;
        long sumCalls;

        Run(CommandSourceStack source) {
            this.source = source;
            this.level = source.getLevel();
        }
    }

    private StressMeasure() {
    }

    static int start(CommandSourceStack source) {
        if (!listening) {
            MinecraftForge.EVENT_BUS.addListener(StressMeasure::onServerTick);
            listening = true;
        }
        boolean restarted = run != null;
        run = new Run(source);
        boolean spark = ModList.get().isLoaded("spark");
        String message = (restarted ? "Started over. " : "")
                + "Measuring pipes in this dimension: 10 s to settle, then 3 readings of 30 s each"
                + (spark ? ", then spark for 60 s" : "") + ". Stay where you are.";
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    /** Called when the server stops, so a measurement never carries over into the next world. */
    public static void clear() {
        run = null;
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tick();
        }
    }

    private static void tick() {
        Run current = run;
        if (current == null) {
            return;
        }
        NetworkManager manager = NetworkManager.getExisting(current.level);
        if (manager == null) { // the dimension was unloaded
            run = null;
            return;
        }
        current.ticks++;
        if (current.ticks == SETTLE_TICKS) {
            manager.resetPeaks();
            String counts = String.format(Locale.ROOT,
                    "%,d networks, %,d pipes, %,d pulling pipes in this dimension. First reading in 30 s.",
                    manager.networkCount(), manager.trackedPipeCount(), manager.extractorCount());
            current.source.sendSuccess(() -> Component.literal(counts), false);
            return;
        }
        if (current.ticks > SETTLE_TICKS && (current.ticks - SETTLE_TICKS) % READING_TICKS == 0) {
            reading(current, manager);
            if (current.readings >= READINGS) {
                finish(current);
                run = null;
            }
        }
    }

    private static void reading(Run current, NetworkManager manager) {
        int ticks = Math.max(1, manager.peakTicks());
        double totalMs = manager.peakTotalNanos() / 1_000_000.0;
        double avgMs = totalMs / ticks;
        double busiestMs = manager.peakTickNanos() / 1_000_000.0;
        long calls = manager.lastSecondCalls();
        int hits = manager.lastSecondBudgetHits();
        long items = manager.peakItemsMoved();
        current.readings++;
        current.sumMs += avgMs;
        current.minMs = Math.min(current.minMs, avgMs);
        current.maxMs = Math.max(current.maxMs, avgMs);
        current.busiestMs = Math.max(current.busiestMs, busiestMs);
        current.sumCalls += calls;
        String line = String.format(Locale.ROOT,
                "Reading %d of %d (%.1f s): %.2f ms/tick (%s), busiest tick %.2f ms, %,d calls/s, limit hit on %d of "
                        + "20 ticks%s, %,d items moved%s",
                current.readings, READINGS, manager.peakTicks() / 20.0, avgMs, DaisychainCommands.healthRating(avgMs),
                busiestMs, calls, hits,
                hits > 0 ? String.format(Locale.ROOT, " (up to %,d waited)", manager.lastSecondMaxWaiting()) : "",
                items, items > 0 ? String.format(Locale.ROOT, " (%.4f ms per 1,000)", totalMs / (items / 1000.0)) : "");
        current.source.sendSuccess(() -> Component.literal(line), false);
        manager.resetPeaks();
    }

    private static void finish(Run current) {
        double avgMs = current.sumMs / READINGS;
        String summary = String.format(Locale.ROOT,
                "Average of %d readings: %.2f ms/tick (%.2f to %.2f), %s; busiest tick %.2f ms; %,d calls/s.",
                READINGS, avgMs, current.minMs, current.maxMs, DaisychainCommands.healthRating(avgMs),
                current.busiestMs, current.sumCalls / READINGS);
        current.source.sendSuccess(() -> Component.literal(summary).withStyle(ChatFormatting.AQUA), false);
        if (ModList.get().isLoaded("spark")) {
            current.source.sendSuccess(() -> Component.literal(
                    "Starting spark for 60 s. It posts its link in chat when it's done."), false);
            current.source.getServer().getCommands().performPrefixedCommand(current.source, SPARK_COMMAND);
        } else {
            current.source.sendSuccess(() -> Component.literal("spark isn't installed, so no profile this time."),
                    false);
        }
    }
}
