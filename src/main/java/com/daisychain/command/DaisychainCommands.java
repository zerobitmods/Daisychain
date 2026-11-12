package com.daisychain.command;

import com.daisychain.Daisychain;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.network.NetworkManager;
import com.daisychain.network.PipeNetwork;
import com.daisychain.item.SettingsCopierItem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import java.util.Locale;

/**
 * /daisychain admin commands (operators only), plus "undo"/"redo" for the Settings Copier (every player).
 * "stress" is registered only in the development game.
 */
public final class DaisychainCommands {

    private DaisychainCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Every player can use "undo" and "redo" (Settings Copier); the rest is for operators.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("daisychain");
        root.then(Commands.literal("network")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> showNetwork(context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos")))))
                .then(Commands.literal("stats")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> showStats(context.getSource())))
                .then(Commands.literal("version")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> showVersion(context.getSource())))
                .then(Commands.literal("undo")
                        .requires(source -> source.getEntity() instanceof net.minecraft.server.level.ServerPlayer)
                        .executes(context -> {
                            SettingsCopierItem.undo(context.getSource().getPlayerOrException(), 0); // the newest
                            return 1;
                        })
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    SettingsCopierItem.undo(context.getSource().getPlayerOrException(),
                                            IntegerArgumentType.getInteger(context, "id"));
                                    return 1;
                                })))
                .then(Commands.literal("redo")
                        .requires(source -> source.getEntity() instanceof net.minecraft.server.level.ServerPlayer)
                        .executes(context -> {
                            SettingsCopierItem.redo(context.getSource().getPlayerOrException());
                            return 1;
                        }));
        // "stress" builds test networks; it only exists in the development game (runClient / IntelliJ),
        // never in a released jar (step 52).
        if (!FMLEnvironment.production) {
            root.then(StressTest.command().requires(source -> source.hasPermission(2)));
            root.then(TestBench.command().requires(source -> source.hasPermission(2))); // step 96
        }
        dispatcher.register(root);
    }

    /** Which build this is: the mod version plus the development step (Daisychain.DEV_STEP). */
    private static int showVersion(CommandSourceStack source) {
        String version = ModList.get().getModContainerById(Daisychain.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("?");
        source.sendSuccess(() -> Component.literal("Daisychain " + version + ", step " + Daisychain.DEV_STEP), false);
        return 1;
    }

    private static int showNetwork(CommandSourceStack source, BlockPos pos) {
        NetworkManager manager = NetworkManager.get(source.getLevel());
        boolean wasCached = manager.isCached(pos);
        PipeNetwork network = manager.getOrBuild(pos);
        if (network == null) {
            source.sendFailure(Component.literal("No Daisychain pipe at " + pos.toShortString()));
            return 0;
        }
        String message = String.format(Locale.ROOT,
                "Network #%d: %d pipes, %d connections (%d pulling), %s %.3f ms%s",
                network.id(),
                network.pipeCount(),
                network.endpointCount(),
                network.extractCount(),
                wasCached ? "cached, was built in" : "built in",
                network.buildNanos() / 1_000_000.0,
                network.isComplete() ? "" : " (continues into unloaded chunks)");
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int showStats(CommandSourceStack source) {
        NetworkManager manager = NetworkManager.get(source.getLevel());
        String message = String.format(Locale.ROOT,
                "%d networks cached, %d pipes and %d chunks tracked, %d pulling pipes in this dimension",
                manager.networkCount(), manager.trackedPipeCount(), manager.trackedChunkCount(),
                manager.extractorCount());
        source.sendSuccess(() -> Component.literal(message), false);
        String budget = String.format(Locale.ROOT,
                "Last second: %d inventory calls, pipes took %.3f ms per tick on average, busiest tick %.3f ms. "
                        + "Limit per tick: %d calls / %.1f ms. Limit reached on %d of 20 ticks%s",
                manager.lastSecondCalls(), manager.lastSecondAverageNanos() / 1_000_000.0,
                manager.lastSecondMaxNanos() / 1_000_000.0,
                DaisychainConfig.MAX_HANDLER_CALLS_PER_TICK.getAsInt(),
                DaisychainConfig.MAX_MILLISECONDS_PER_TICK.getAsDouble(),
                manager.lastSecondBudgetHits(),
                manager.lastSecondBudgetHits() > 0
                        ? " (up to " + manager.lastSecondMaxWaiting() + " pulling pipes waited a tick)" : "");
        source.sendSuccess(() -> Component.literal(budget), false);
        // Step 37: one-off costs since the previous /daisychain stats (e.g. a big network rebuilt after a pipe change).
        String peaks = String.format(Locale.ROOT,
                "Since the last /daisychain stats: slowest tick %.3f ms; %d networks built, slowest %.3f ms; "
                        + "%d pulling pipes re-sorted their destinations, %.3f ms in total",
                manager.peakTickNanos() / 1_000_000.0, manager.peakBuilds(), manager.peakBuildNanos() / 1_000_000.0,
                manager.peakBinds(), manager.peakBindNanos() / 1_000_000.0);
        source.sendSuccess(() -> Component.literal(peaks), false);
        // Step 113: the cost per item over the whole time since the last stats, so a quiet second can't skew it.
        long items = manager.peakItemsMoved();
        int ticks = Math.max(1, manager.peakTicks());
        double totalMs = manager.peakTotalNanos() / 1_000_000.0;
        String itemsLine = String.format(Locale.ROOT,
                "Since the last /daisychain stats (%.1f s): %,d items moved; all pipe work took %.3f ms per tick on "
                        + "average%s",
                manager.peakTicks() / 20.0, items, totalMs / ticks,
                items > 0 ? String.format(Locale.ROOT, ", %.4f ms per 1,000 items", totalMs / (items / 1000.0)) : "");
        source.sendSuccess(() -> Component.literal(itemsLine), false);
        // Step 114: keep what the health summary needs before the peaks reset. Under 2 s since the last stats is too
        // short to judge, so the last full second is used instead.
        boolean shortWindow = manager.peakTicks() < 40;
        double healthAvgMs = shortWindow ? manager.lastSecondAverageNanos() / 1_000_000.0 : totalMs / ticks;
        double healthSeconds = shortWindow ? 1.0 : manager.peakTicks() / 20.0;
        double slowestMs = manager.peakTickNanos() / 1_000_000.0;
        double slowestBuildMs = manager.peakBuildNanos() / 1_000_000.0;
        manager.resetPeaks();
        // Review R17: what the chunk ticket listener did, to see what it costs on a busy server.
        long[] tickets = NetworkManager.ticketCounters();
        String ticketLine = String.format(Locale.ROOT,
                "Chunk tickets since the last /daisychain stats (all dimensions): %d changes seen, %d crossed the "
                        + "loaded line, %d of those in chunks with pipes, %d networks dropped to be rebuilt",
                tickets[0], tickets[1], tickets[2], tickets[3]);
        source.sendSuccess(() -> Component.literal(ticketLine), false);
        NetworkManager.resetTicketCounters();
        sendHealth(source, healthAvgMs, healthSeconds, slowestMs, slowestBuildMs, manager.lastSecondBudgetHits());
        return 1;
    }

    // Step 114: cut-offs for the health summary, in ms of pipe work per tick on average (a tick is 50 ms).
    private static final double HEALTH_BUSY_MS = 2.0;
    private static final double HEALTH_EXPENSIVE_MS = 5.0;
    /** Step 115: the rating word for an average pipe time per tick (also used by /daisychain stress measure). */
    static String healthRating(double avgMs) {
        return avgMs >= HEALTH_EXPENSIVE_MS ? "EXPENSIVE" : avgMs >= HEALTH_BUSY_MS ? "BUSY" : "HEALTHY";
    }

    /** A single tick at least this slow marks the summary "spiky". */
    private static final double HEALTH_SPIKE_MS = 20.0;

    /**
     * Step 114: the last line(s) of /daisychain stats, a plain rating of what pipes cost the server.
     * HEALTHY under 2 ms per tick, BUSY 2 to 5 ms, EXPENSIVE 5 ms or more. Extra lines when pipes hit the per-tick
     * limit (they slow down, the server doesn't) or one tick took 20 ms or more.
     */
    private static void sendHealth(CommandSourceStack source, double avgMs, double seconds, double slowestMs,
                                   double slowestBuildMs, int budgetHits) {
        String rating;
        String note;
        ChatFormatting color;
        if (avgMs >= HEALTH_EXPENSIVE_MS) {
            rating = "EXPENSIVE";
            note = ", eating into server time.";
            color = ChatFormatting.RED;
        } else if (avgMs >= HEALTH_BUSY_MS) {
            rating = "BUSY";
            note = ". Fine, but worth watching.";
            color = ChatFormatting.YELLOW;
        } else {
            rating = "HEALTHY";
            note = ".";
            color = ChatFormatting.GREEN;
        }
        boolean spiky = slowestMs >= HEALTH_SPIKE_MS;
        String headline = String.format(Locale.ROOT,
                "Pipe health (last %.0f s): %s%s. Pipes use %.2f ms of each 50 ms tick (%.1f%%)%s",
                seconds, rating, spiky ? ", but spiky" : "", avgMs, avgMs / 50.0 * 100.0, note);
        source.sendSuccess(() -> Component.literal(headline).withStyle(color), false);
        if (budgetHits > 0) {
            String limitLine = String.format(Locale.ROOT,
                    "  Pipes hit the per-tick limit (%,d calls / %.1f ms) on %d of the last 20 ticks, so some transfers "
                            + "wait a tick. This slows pipes down; it doesn't lag the server.",
                    DaisychainConfig.MAX_HANDLER_CALLS_PER_TICK.getAsInt(),
                    DaisychainConfig.MAX_MILLISECONDS_PER_TICK.getAsDouble(), budgetHits);
            source.sendSuccess(() -> Component.literal(limitLine).withStyle(ChatFormatting.YELLOW), false);
        }
        if (spiky) {
            // A rebuild that took at least half the slowest tick explains it; otherwise say it wasn't one.
            String spikeLine = slowestBuildMs >= slowestMs / 2.0
                    ? String.format(Locale.ROOT,
                            "  Slowest tick %.1f ms, caused by a big network being rebuilt (%.1f ms).",
                            slowestMs, slowestBuildMs)
                    : String.format(Locale.ROOT,
                            "  Slowest tick %.1f ms, not a network rebuild (slowest %.1f ms). If it shows up again "
                                    + "with nothing changed, catch it with /spark profiler.",
                            slowestMs, slowestBuildMs);
            source.sendSuccess(() -> Component.literal(spikeLine).withStyle(ChatFormatting.YELLOW), false);
        }
    }
}
