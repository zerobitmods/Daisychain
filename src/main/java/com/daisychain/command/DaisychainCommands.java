package com.daisychain.command;

import com.daisychain.Daisychain;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.network.NetworkManager;
import com.daisychain.network.PipeNetwork;
import com.daisychain.item.SettingsCopierItem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
        manager.resetPeaks();
        return 1;
    }
}
