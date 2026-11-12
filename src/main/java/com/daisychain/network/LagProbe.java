package com.daisychain.network;

import com.daisychain.Daisychain;
import com.daisychain.block.PipeKind;
import com.daisychain.command.DaisychainCommands;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.item.UpgradeTier;
import com.daisychain.net.DaisychainNetwork;
import com.daisychain.net.LagHighlightPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.UUID;

/**
 * Step F45 (port of step 116): /daisychain lag. For a set number of seconds, every transfer in one dimension is timed and counted per pull
 * side; then the busiest networks are listed (time per tick, share, calls, what they move, their busiest side and a
 * plain "why"), and the busiest pull sides of those networks are outlined in red for the operator who asked.
 * <p>
 * An object of this class only exists while a measurement runs (NetworkManager.lagProbe). The rest of the time pipes
 * pay one null check per transfer and nothing else. While it runs, each transfer costs two System.nanoTime() calls and
 * a few additions; each pulling pipe gets one Sample the first time it is seen (dropped again at the end).
 */
final class LagProbe {
    /** Networks listed in the report. */
    private static final int TOP_NETWORKS = 5;
    /** Busiest pull sides outlined per listed network. */
    private static final int OUTLINED_SIDES_PER_NETWORK = 3;
    /** Microseconds per inventory call above which a side's inventories count as slow (ours measure under 1). */
    private static final double SLOW_CALL_MICROS = 5.0;
    /** A side needs at least this many calls before its time per call means anything. */
    private static final long MIN_CALLS_FOR_SPEED = 20;
    /** Inventory calls per transfer at or above which transfers count as very big. */
    private static final double BIG_TRANSFER_CALLS = 256;
    /** "Destinations full" needs at least this many calls per transfer, so cheap empty checks aren't blamed. */
    private static final double FULL_DESTINATION_CALLS = 32;

    /** What one pulling pipe did while measured, per side (index Direction.get3DDataValue()). */
    static final class Sample {
        final long[] nanos = new long[6];
        final long[] calls = new long[6];
        final int[] runs = new int[6];
        /** Transfers that moved nothing. */
        final int[] emptyRuns = new int[6];
        /** Amount moved per resource and side (index resource * 6 + side): items, mB or FE. */
        final long[] moved = new long[PipeKind.RESOURCE_COUNT * 6];
        /** Ticks this pipe was due but had to wait for the per-tick limit. */
        int waits;
        /** The network the pipe was in at its last transfer. */
        @Nullable
        PipeNetwork network;
    }

    private final CommandSourceStack source;
    @Nullable
    private final UUID playerId;
    final int targetTicks;
    int ticks;
    private long totalNanos;
    private int budgetHits;
    private int maxWaiting;
    private final ArrayList<Extractor> sampled = new ArrayList<>();

    LagProbe(CommandSourceStack source, int seconds) {
        this.source = source;
        this.playerId = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        this.targetTicks = Math.max(1, seconds) * 20;
    }

    private Sample sample(Extractor extractor) {
        Sample sample = extractor.lag;
        if (sample == null) {
            sample = new Sample();
            extractor.lag = sample;
            sampled.add(extractor);
        }
        return sample;
    }

    /** One transfer of one resource from one pull side finished. */
    void transferDone(Extractor extractor, int side, int resource, long nanos, int calls, int moved) {
        Sample sample = sample(extractor);
        sample.nanos[side] += nanos;
        sample.calls[side] += calls;
        sample.runs[side]++;
        if (moved > 0) {
            sample.moved[resource * 6 + side] += moved;
        } else {
            sample.emptyRuns[side]++;
        }
        sample.network = extractor.sortedFor;
    }

    /** The pipe was due this tick but the per-tick limit ran out before (all of) its turn. */
    void waited(Extractor extractor) {
        sample(extractor).waits++;
    }

    /** End of one tick of pipe work. True when the time is up. */
    boolean recordTick(long nanos, boolean hitBudget, int waiting) {
        totalNanos += nanos;
        if (hitBudget) {
            budgetHits++;
        }
        maxWaiting = Math.max(maxWaiting, waiting);
        return ++ticks >= targetTicks;
    }

    /** Sends the report and the outlines, then lets go of every Sample. Never throws. */
    void finish(ServerLevel level, NetworkManager manager) {
        try {
            report(level, manager);
        } catch (RuntimeException e) {
            Daisychain.LOGGER.error("Daisychain: /daisychain lag couldn't finish its report", e);
            send(level, Component.literal("The lag report failed; see the server log.").withStyle(ChatFormatting.RED));
        } finally {
            for (int i = 0; i < sampled.size(); i++) {
                sampled.get(i).lag = null;
            }
            sampled.clear();
        }
    }

    /** Called when the measurement is dropped without a report (the dimension's pipes were cleared). */
    void release() {
        for (int i = 0; i < sampled.size(); i++) {
            sampled.get(i).lag = null;
        }
        sampled.clear();
    }

    // ---------------------------------------------------------------- report (runs once, at the end)

    /** One busy pull side, for sorting. */
    private record SideRef(Extractor extractor, int side, long nanos) {
    }

    /** Everything measured for one network. */
    private static final class NetStat {
        long nanos;
        long calls;
        long runs;
        long emptyRuns;
        long waits;
        final long[] moved = new long[PipeKind.RESOURCE_COUNT];
        final ArrayList<Extractor> pipes = new ArrayList<>();
        final ArrayList<SideRef> sides = new ArrayList<>();
    }

    private void report(ServerLevel level, NetworkManager manager) {
        double tickCount = Math.max(1, ticks);
        double seconds = ticks / 20.0;
        double avgMs = totalNanos / 1_000_000.0 / tickCount;

        IdentityHashMap<PipeNetwork, NetStat> byNetwork = new IdentityHashMap<>();
        long transferNanos = 0;
        for (int i = 0; i < sampled.size(); i++) {
            Extractor extractor = sampled.get(i);
            Sample sample = extractor.lag;
            if (sample == null) {
                continue;
            }
            PipeNetwork network = sample.network != null ? sample.network : extractor.sortedFor;
            if (network == null || !network.valid) {
                // Rebuilt while measuring: count it with the network that is there now, if it's built.
                PipeNetwork current = manager.cachedNetworkAt(extractor.pos);
                if (current != null) {
                    network = current;
                }
            }
            if (network == null) {
                continue;
            }
            NetStat stat = byNetwork.computeIfAbsent(network, key -> new NetStat());
            stat.pipes.add(extractor);
            stat.waits += sample.waits;
            for (int side = 0; side < 6; side++) {
                if (sample.runs[side] == 0) {
                    continue;
                }
                stat.nanos += sample.nanos[side];
                stat.calls += sample.calls[side];
                stat.runs += sample.runs[side];
                stat.emptyRuns += sample.emptyRuns[side];
                for (int resource = 0; resource < PipeKind.RESOURCE_COUNT; resource++) {
                    stat.moved[resource] += sample.moved[resource * 6 + side];
                }
                stat.sides.add(new SideRef(extractor, side, sample.nanos[side]));
                transferNanos += sample.nanos[side];
            }
        }

        ArrayList<NetStat> ranked = new ArrayList<>();
        for (NetStat stat : byNetwork.values()) {
            if (!stat.sides.isEmpty()) {
                stat.sides.sort((a, b) -> Long.compare(b.nanos(), a.nanos()));
                ranked.add(stat);
            }
        }
        ranked.sort((a, b) -> Long.compare(b.nanos, a.nanos));

        String rating = DaisychainCommands.healthRating(avgMs);
        MutableComponent header = Component.literal(String.format(Locale.ROOT,
                "Pipe work: %s ms per tick on average over %.0f s (limit %s ms), ", ms(avgMs), seconds,
                trim(DaisychainConfig.MAX_MILLISECONDS_PER_TICK.get())));
        header.append(Component.literal(rating).withStyle(DaisychainCommands.healthColor(avgMs)));
        if (ranked.isEmpty()) {
            header.append(". No pipe moved or tried to move anything in this dimension.");
            send(level, header);
            return;
        }
        header.append(ranked.size() == 1 ? ". 1 working network:" : String.format(Locale.ROOT,
                ". Busiest %d of %,d working networks:", Math.min(TOP_NETWORKS, ranked.size()), ranked.size()));
        send(level, header);

        int shown = Math.min(TOP_NETWORKS, ranked.size());
        ArrayList<SideRef> outlined = new ArrayList<>();
        for (int n = 0; n < shown; n++) {
            NetStat stat = ranked.get(n);
            double netMs = stat.nanos / 1_000_000.0 / tickCount;
            double share = totalNanos > 0 ? stat.nanos * 100.0 / totalNanos : 0;
            BlockPos near = nearestToMiddle(stat.pipes);
            MutableComponent first = Component.literal(String.format(Locale.ROOT, " %d. %s ms/tick (%s) near %s ",
                    n + 1, ms(netMs), percent(share), coords(near)));
            first.append(teleport(level, near, "[teleport]"));
            send(level, first);

            StringBuilder second = new StringBuilder(String.format(Locale.ROOT, "    %d pull side%s, %s calls/tick",
                    stat.sides.size(), stat.sides.size() == 1 ? "" : "s", compact(stat.calls / tickCount)));
            if (stat.moved[PipeKind.ITEMS] > 0) {
                second.append(", ").append(compact(stat.moved[PipeKind.ITEMS] / seconds)).append(" items/s");
            }
            if (stat.moved[PipeKind.FLUIDS] > 0) {
                second.append(", ").append(compact(stat.moved[PipeKind.FLUIDS] / seconds)).append(" mB/s");
            }
            if (stat.moved[PipeKind.ENERGY_RES] > 0) {
                second.append(", ").append(compact(stat.moved[PipeKind.ENERGY_RES] / tickCount)).append(" FE/t");
            }
            if (stat.moved[PipeKind.ITEMS] + stat.moved[PipeKind.FLUIDS] + stat.moved[PipeKind.ENERGY_RES] == 0) {
                second.append(", moved nothing");
            }
            if (stat.waits > 0) {
                second.append(String.format(Locale.ROOT, ", waited on the limit %,d times", stat.waits));
            }
            send(level, Component.literal(second.toString()).withStyle(ChatFormatting.GRAY));

            SideRef top = stat.sides.get(0);
            Direction direction = Direction.from3DDataValue(top.side());
            MutableComponent third = Component.literal("    Busiest side: ").withStyle(ChatFormatting.GRAY);
            third.append(teleport(level, top.extractor().pos, coords(top.extractor().pos)));
            third.append(Component.literal(" (" + direction.getName() + "), ").withStyle(ChatFormatting.GRAY));
            third.append(tierLabel(top.extractor(), direction));
            send(level, third);

            MutableComponent why = whyLine(level, stat, top);
            if (why != null) {
                send(level, why);
            }
            for (int s = 0; s < stat.sides.size() && s < OUTLINED_SIDES_PER_NETWORK; s++) {
                outlined.add(stat.sides.get(s));
            }
        }

        if (budgetHits > 0) {
            send(level, Component.literal(String.format(Locale.ROOT,
                    "Pipes hit the per-tick limit on %,d of %,d ticks (up to %,d pulling pipes waited a tick). They "
                            + "slow down; the server doesn't.", budgetHits, ticks, maxWaiting))
                    .withStyle(ChatFormatting.YELLOW));
        }
        double transferMs = transferNanos / 1_000_000.0 / tickCount;
        send(level, Component.literal(String.format(Locale.ROOT,
                "Moving things took %s of the %s ms per tick; the rest is finding which pipes are due and "
                        + "rebuilding networks that changed.", ms(Math.min(transferMs, avgMs)), ms(avgMs)))
                .withStyle(ChatFormatting.DARK_GRAY));

        ServerPlayer player = player(level);
        if (player != null && !outlined.isEmpty()) {
            // Step 116c: sent even if the operator is in another dimension now; they show once they're back.
            long[] positions = new long[outlined.size()];
            byte[] sides = new byte[outlined.size()];
            for (int i = 0; i < outlined.size(); i++) {
                positions[i] = outlined.get(i).extractor().pos.asLong();
                sides[i] = (byte) outlined.get(i).side();
            }
            DaisychainNetwork.sendToPlayer(player, new LagHighlightPayload(level.dimension().location(), positions,
                    sides));
            String where = player.serverLevel() == level ? ""
                    : " once you're back in " + level.dimension().location();
            send(level, Component.literal(String.format(Locale.ROOT,
                    "The busiest pull sides of these networks glow red for %d s%s (only you see them).",
                    LagHighlightPayload.SECONDS, where)).withStyle(ChatFormatting.RED));
        }
    }

    /** The plain "Why" line for a network, or null if nothing stands out. */
    @Nullable
    private static MutableComponent whyLine(ServerLevel level, NetStat stat, SideRef top) {
        Sample sample = top.extractor().lag;
        int side = top.side();
        double callsPerRun = stat.runs > 0 ? stat.calls / (double) stat.runs : 0;
        double emptyShare = stat.runs > 0 ? stat.emptyRuns / (double) stat.runs : 0;
        String reason;
        MutableComponent extra = null;
        if (sample != null && sample.calls[side] >= MIN_CALLS_FOR_SPEED
                && sample.nanos[side] / 1000.0 / sample.calls[side] >= SLOW_CALL_MICROS) {
            reason = String.format(Locale.ROOT, "slow inventories, %.1f µs per call on the busiest side (normal is "
                    + "under 1)", sample.nanos[side] / 1000.0 / sample.calls[side]);
            Direction direction = Direction.from3DDataValue(side);
            BlockState neighbor = Loaded.stateIfLoaded(level, top.extractor().pos.relative(direction));
            if (neighbor != null && !neighbor.isAir()) {
                extra = Component.literal(". It pulls from: ").append(neighbor.getBlock().getName());
            }
        } else if (emptyShare >= 0.5 && callsPerRun >= FULL_DESTINATION_CALLS) {
            reason = String.format(Locale.ROOT, "destinations are full: %.0f%% of tries moved nothing, and each try "
                    + "still asks about %,.0f slots", emptyShare * 100.0, callsPerRun);
        } else if (callsPerRun >= BIG_TRANSFER_CALLS) {
            reason = String.format(Locale.ROOT, "very big transfers, about %,.0f inventory calls each (big storage "
                    + "blocks or top upgrades)", callsPerRun);
        } else {
            reason = "nothing unusual, it just moves a lot";
        }
        MutableComponent line = Component.literal("    Why: " + reason).withStyle(ChatFormatting.GRAY);
        if (extra != null) {
            line.append(extra.withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    /** "item pipe, Nether Star upgrade" / "energy pipes, Diamond tier". */
    private static MutableComponent tierLabel(Extractor extractor, Direction direction) {
        int tier = extractor.speedTier(extractor.data, direction);
        Component tierName = Component.translatable("upgrade.daisychain.tier." + UpgradeTier.byLevel(tier).id());
        MutableComponent label = Component.literal("").withStyle(ChatFormatting.GRAY);
        switch (extractor.kind) {
            case ENERGY -> label.append("energy pipe, ").append(tierName).append(" tier");
            case FLUID -> label.append("fluid pipe, ");
            case UNIVERSAL -> label.append("universal pipe, ");
            default -> label.append("item pipe, ");
        }
        if (extractor.kind != PipeKind.ENERGY) {
            if (tier == 0) {
                label.append("no upgrade");
            } else {
                label.append(tierName).append(" upgrade");
            }
        }
        return label;
    }

    /** The measured pipe closest to the middle of the network's measured pipes (always a real pipe position). */
    private static BlockPos nearestToMiddle(ArrayList<Extractor> pipes) {
        double x = 0;
        double y = 0;
        double z = 0;
        for (int i = 0; i < pipes.size(); i++) {
            BlockPos pos = pipes.get(i).pos;
            x += pos.getX();
            y += pos.getY();
            z += pos.getZ();
        }
        x /= pipes.size();
        y /= pipes.size();
        z /= pipes.size();
        BlockPos best = pipes.get(0).pos;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < pipes.size(); i++) {
            BlockPos pos = pipes.get(i).pos;
            double dx = pos.getX() - x;
            double dy = pos.getY() - y;
            double dz = pos.getZ() - z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Clickable text that teleports the operator onto the pipe, in the measured dimension. */
    private static MutableComponent teleport(ServerLevel level, BlockPos pos, String text) {
        String command = String.format(Locale.ROOT, "/execute in %s run tp @s %.1f %d %.1f",
                level.dimension().location(), pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        return Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Teleport onto the pipe at " + coords(pos)))));
    }

    /** 1,210 / 40k / 2.1M */
    private static String compact(double value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 10_000) {
            return String.format(Locale.ROOT, "%.0fk", value / 1_000.0);
        }
        if (value > 0 && value < 10) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    /**
     * Step 116b: milliseconds with enough digits that a small network doesn't read as free: 1.84, 0.41, 0.0041,
     * 0.00007; exactly 0 is "0".
     */
    private static String ms(double value) {
        if (value <= 0) {
            return "0";
        }
        if (value >= 0.1) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        if (value >= 0.001) {
            return String.format(Locale.ROOT, "%.4f", value);
        }
        return String.format(Locale.ROOT, "%.5f", value);
    }

    /** Step 116c: a share with a decimal when it's small, so a real network never shows as 0%: 50%, 4.2%, 0.3%, <0.1%. */
    private static String percent(double value) {
        if (value <= 0) {
            return "0%";
        }
        if (value >= 9.95) {
            return String.format(Locale.ROOT, "%.0f%%", value);
        }
        if (value >= 0.05) {
            return String.format(Locale.ROOT, "%.1f%%", value);
        }
        return "<0.1%";
    }

    /** 10 instead of 10.0. */
    private static String trim(double value) {
        return value == Math.rint(value) ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    /** The operator who ran the command (looked up again: they may have respawned or left), or null. */
    @Nullable
    private ServerPlayer player(ServerLevel level) {
        return playerId == null ? null : level.getServer().getPlayerList().getPlayer(playerId);
    }

    /** To the operator who asked (if still online), or to the console / command block that asked. */
    private void send(ServerLevel level, Component message) {
        if (playerId != null) {
            ServerPlayer player = player(level);
            if (player != null) {
                player.sendSystemMessage(message);
            }
            return;
        }
        source.sendSuccess(() -> message, false);
    }
}
