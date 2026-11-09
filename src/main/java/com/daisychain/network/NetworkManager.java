package com.daisychain.network;

import com.daisychain.block.EnergyPipeBlock;
import com.daisychain.block.FlowPipeBlock;
import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.block.RedstoneMode;
import com.daisychain.block.SideState;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.config.UpgradeSpeeds;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Keeps track of pipe networks in one dimension.
 * Networks are built lazily (the first time something asks) and dropped when
 * any pipe in them, or next to them, changes. Nothing here runs every tick.
 */
public final class NetworkManager {
    private static final Map<ServerLevel, NetworkManager> MANAGERS = new IdentityHashMap<>();
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Chunk events that arrived off the server thread; handled at the start of the next server tick. */
    private static final Queue<PendingChunkEvent> PENDING_CHUNK_EVENTS = new ConcurrentLinkedQueue<>();
    /** F40: chunks that loaded, scanned at the start of the next server tick for pipes touching block entities. */
    private static final Queue<PendingChunkEvent> PENDING_CHUNK_SCANS = new ConcurrentLinkedQueue<>();

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<PipeNetwork> networkByPipe = new Long2ObjectOpenHashMap<>();
    private final ReferenceOpenHashSet<PipeNetwork> networks = new ReferenceOpenHashSet<>();
    /** For each chunk: networks that have pipes in it or stop at its edge waiting for it to load. */
    private final Long2ObjectOpenHashMap<ReferenceArrayList<PipeNetwork>> networksByChunk = new Long2ObjectOpenHashMap<>();
    /** Pipes with at least one pulling side, registered by their block entity. */
    private final Long2ObjectOpenHashMap<Extractor> extractorsByPos = new Long2ObjectOpenHashMap<>();
    private final ObjectArrayList<Extractor> extractors = new ObjectArrayList<>();
    /** F40: pipe sides waiting for a block entity next to them to start offering something. */
    public final SideRecheck sideRecheck;

    private NetworkManager(ServerLevel level) {
        this.level = level;
        this.sideRecheck = new SideRecheck(level);
    }

    public static NetworkManager get(ServerLevel level) {
        return MANAGERS.computeIfAbsent(level, NetworkManager::new);
    }

    /** Like get, but never creates a manager (used during unload/shutdown). */
    @Nullable
    public static NetworkManager getExisting(ServerLevel level) {
        return MANAGERS.get(level);
    }

    // ---------------------------------------------------------------- pulling sides

    /** Registers, updates or (with mask 0) removes the pulling sides of the pipe at pos. */
    public void setExtractor(BlockPos pos, int extractMask, @Nullable PipeDataBlockEntity data) {
        long key = pos.asLong();
        Extractor extractor = extractorsByPos.get(key);
        if (extractMask == 0 || data == null) {
            if (extractor != null) {
                extractorsByPos.remove(key);
                removeFromList(extractor);
            }
            return;
        }
        if (extractor == null) {
            extractor = new Extractor(pos.immutable());
            extractor.index = extractors.size();
            extractors.add(extractor);
            extractorsByPos.put(key, extractor);
        }
        extractor.extractMask = extractMask;
        extractor.data = data;
        extractor.kind = FlowPipeBlock.kindOf(data.getBlockState());
        extractor.cachedState = null; // read the pipe's block state again on its next run
        extractor.wake();
    }

    /** A block next to this pipe changed: pull sides asleep with nothing to pull from look again (step 34). */
    public void onNeighborChanged(BlockPos pos) {
        Extractor extractor = extractorsByPos.get(pos.asLong());
        if (extractor != null) {
            extractor.wakeIdleSides();
        }
    }

    /** A pull side's settings changed: run it again soon. */
    public void wakeExtractor(BlockPos pos) {
        Extractor extractor = extractorsByPos.get(pos.asLong());
        if (extractor != null) {
            extractor.wake();
        }
    }

    /**
     * A neighbor of a pipe with settings changed; its redstone signal may have changed.
     * Never lets a redstone clock make a side run faster than its normal interval.
     */
    public void onRedstoneMaybeChanged(BlockPos pos) {
        updateInsertRedstone(pos);
        Extractor extractor = extractorsByPos.get(pos.asLong());
        if (extractor == null) {
            return;
        }
        if (!signalReadable(pos)) {
            return; // part of the area around the pipe isn't loaded: keep the last known state
        }
        boolean powered = level.hasNeighborSignal(pos);
        if (extractor.poweredKnown && powered == extractor.lastPowered) {
            return;
        }
        if (extractor.poweredKnown && powered) {
            extractor.pendingPulse = 0b111111;
        }
        extractor.poweredKnown = true;
        extractor.lastPowered = powered;
        PipeDataBlockEntity data = extractor.data;
        long now = level.getGameTime();
        long next = Long.MAX_VALUE;
        for (Direction direction : DIRECTIONS) {
            int i = direction.get3DDataValue();
            if ((extractor.extractMask & (1 << i)) != 0) {
                int tier = extractor.speedTier(data, direction);
                long earliest = Math.max(now, extractor.sideLastRun[i] + UpgradeSpeeds.interval(extractor.kind, tier));
                extractor.sideNextRun[i] = Math.min(extractor.sideNextRun[i], earliest);
                extractor.sideBackoff[i] = 1;
            }
            next = Math.min(next, extractor.sideNextRun[i]);
        }
        extractor.nextRun = next;
    }

    /**
     * Step 40: insert sides with a redstone mode on the pipe at pos follow its signal. Only that pipe's endpoints in the
     * network's (usually empty) redstone list are touched. A destination that just opened wakes the network's pulling
     * pipes, which may have slowed down while everything was refused.
     */
    private void updateInsertRedstone(BlockPos pos) {
        PipeNetwork network = networkByPipe.get(pos.asLong());
        if (network == null || !network.valid || network.redstoneEndpoints.isEmpty()) {
            return;
        }
        int powered = -1; // read once, only if this pipe has such a side
        boolean opened = false;
        for (int i = 0; i < network.redstoneEndpoints.size(); i++) {
            PipeNetwork.Endpoint endpoint = network.redstoneEndpoints.get(i);
            if (!endpoint.pipePos().equals(pos)) {
                continue;
            }
            if (powered < 0) {
                if (!signalReadable(pos)) {
                    return;
                }
                powered = level.hasNeighborSignal(pos) ? 1 : 0;
            }
            boolean wasBlocked = endpoint.redstoneBlocked();
            endpoint.setPowered(powered == 1);
            if (wasBlocked && !endpoint.redstoneBlocked()) {
                opened = true;
            }
        }
        if (opened) {
            for (int i = 0; i < network.boundExtractors.size(); i++) {
                network.boundExtractors.get(i).wake();
            }
        }
    }

    /**
     * True if every chunk within 2 blocks of pos is loaded. Reading a redstone signal looks at the 6 neighbors and, for
     * solid blocks, their neighbors too; with this check that can never load a chunk (border chunks can have unloaded
     * neighbors).
     */
    private boolean signalReadable(BlockPos pos) {
        int minX = SectionPos.blockToSectionCoord(pos.getX() - 2);
        int maxX = SectionPos.blockToSectionCoord(pos.getX() + 2);
        int minZ = SectionPos.blockToSectionCoord(pos.getZ() - 2);
        int maxZ = SectionPos.blockToSectionCoord(pos.getZ() + 2);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunk(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void removeFromList(Extractor extractor) {
        int index = extractor.index;
        if (index < 0 || index >= extractors.size() || extractors.get(index) != extractor) {
            return;
        }
        Extractor last = extractors.remove(extractors.size() - 1);
        if (last != extractor) {
            extractors.set(index, last);
            last.index = index;
        }
        extractor.index = -1;
    }

    /** Step 108: a pull side's status after one transfer of one resource. */
    private static int status(int moved, boolean noDestination, boolean sawItems) {
        if (moved > 0) {
            return SideStatus.MOVING;
        }
        if (noDestination) {
            return SideStatus.NO_DESTINATION;
        }
        return sawItems ? SideStatus.WAITING : SideStatus.NOTHING_TO_PULL;
    }

    /**
     * Step 108: what this pull side did last time it ran (SideStatus), for one resource, or for all of the pipe's
     * resources together when resource is -1 (the most useful one wins). A few array reads; called by the open Pull
     * side screen (each tick) and by Jade (a few times a second), never by the transfer itself.
     */
    public int sideStatus(BlockPos pos, Direction side, int resource) {
        Extractor extractor = extractorsByPos.get(pos.asLong());
        if (extractor == null) {
            return SideStatus.STARTING;
        }
        int i = side.get3DDataValue();
        PipeDataBlockEntity data = extractor.data;
        if (resource >= 0) {
            if (data != null && !data.pullsResource(side, resource)) {
                return SideStatus.SWITCHED_OFF;
            }
            return storedStatus(extractor, resource, i);
        }
        int best = -1;
        for (int r = 0; r < PipeKind.RESOURCE_COUNT; r++) {
            if (!extractor.kind.carries(r) || (data != null && !data.pullsResource(side, r))) {
                continue;
            }
            int status = storedStatus(extractor, r, i);
            best = best < 0 ? status : SideStatus.better(best, status);
        }
        return best < 0 ? SideStatus.SWITCHED_OFF : best;
    }

    /** Step 109: a side that had energy pushed into it in the last second shows Moving, whatever its own pull found. */
    private int storedStatus(Extractor extractor, int resource, int side) {
        if (resource == PipeKind.ENERGY_RES && level.getGameTime() - extractor.energyPushTick[side] <= PUSH_STATUS_TICKS) {
            return SideStatus.MOVING;
        }
        return SideStatus.valid(extractor.sideStatus[resource * 6 + side]);
    }

    // ---------------------------------------------------------------- energy pushed into pull sides (step 109)

    /** Ticks a pull side shows Moving after energy was last pushed into it. */
    private static final int PUSH_STATUS_TICKS = 20;

    /**
     * Energy a block pushes into a pull side of an energy or universal pipe (a Flux Point only pushes and never lets
     * energy be pulled; many generators and cables push too). The FE goes straight on to the network's destinations
     * by the side's own rules and speed, shared with what the side pulls; the pipe keeps none, so whatever isn't
     * taken stays in the pushing block. Called from other mods' code, outside our tick (not counted in the tick budget,
     * bounded by the side's speed and max_handler_calls_per_transfer). Returns the FE taken.
     */
    public int receivePushedEnergy(BlockPos pos, Direction side, int maxReceive, boolean simulate) {
        if (maxReceive <= 0) {
            return 0;
        }
        Extractor extractor = pushTarget(pos, side);
        if (extractor == null) {
            return 0;
        }
        PipeDataBlockEntity data = extractor.data;
        PipeNetwork network = pushNetwork(extractor);
        if (network == null || data == null || extractor.cachedState == null
                || extractor.cachedState.getValue(FlowPipeBlock.propertyFor(side)) != SideState.EXTRACT) {
            return 0;
        }
        int s = side.get3DDataValue();
        int tier = extractor.speedTier(data, side);
        int perTransfer = UpgradeSpeeds.energyFe(tier);
        int batch = data.batch(side, PipeKind.ENERGY_RES);
        if (batch > 0) {
            perTransfer = Math.min(perTransfer, batch);
        }
        long now = level.getGameTime();
        int allowance = extractor.energyAllowance(s, now, UpgradeSpeeds.energyInterval(tier), perTransfer);
        if (allowance <= 0) {
            return 0;
        }
        int moved;
        extractor.busy = true;
        try {
            moved = EnergyTransfer.push(level, extractor, side, extractor.destinations, data.distribution(side),
                    data.allowsSelfFeed(side), Math.min(maxReceive, allowance),
                    data.fillDestinationTo(side, PipeKind.ENERGY_RES),
                    DaisychainConfig.MAX_HANDLER_CALLS_PER_TRANSFER.get(), simulate);
        } finally {
            extractor.busy = false;
        }
        if (!simulate && moved > 0) {
            extractor.addEnergyUsed(s, moved);
            extractor.energyPushTick[s] = now;
        }
        return moved;
    }

    /** Step 109: true if this pull side takes pushed energy right now (asked by pushing blocks before they push). */
    public boolean acceptsPushedEnergy(BlockPos pos, Direction side) {
        return pushTarget(pos, side) != null;
    }

    /** Step 109: the most FE this pull side moves per transfer (shown as the capacity of its energy input). */
    public int pushedEnergyCapacity(BlockPos pos, Direction side) {
        Extractor extractor = extractorsByPos.get(pos.asLong());
        return extractor == null || extractor.data == null ? 0
                : UpgradeSpeeds.energyFe(extractor.speedTier(extractor.data, side));
    }

    /**
     * Step 109: the pulling pipe at pos if this side may take pushed energy now: server thread, the side pulls energy,
     * redstone lets it run (pulse mode never takes pushes), and the pipe isn't already moving something (no loops).
     */
    @Nullable
    private Extractor pushTarget(BlockPos pos, Direction side) {
        if (!level.getServer().isSameThread()) {
            return null;
        }
        Extractor extractor = extractorsByPos.get(pos.asLong());
        if (extractor == null || extractor.busy || !extractor.kind.carries(PipeKind.ENERGY_RES)) {
            return null;
        }
        PipeDataBlockEntity data = extractor.data;
        if (data == null || (extractor.extractMask & (1 << side.get3DDataValue())) == 0
                || !data.pullsResource(side, PipeKind.ENERGY_RES)) {
            return null;
        }
        if (!extractor.poweredKnown && signalReadable(extractor.pos)) {
            extractor.lastPowered = level.hasNeighborSignal(extractor.pos);
            extractor.poweredKnown = true;
        }
        boolean allowed = switch (data.redstoneMode(side)) {
            case ALWAYS -> true;
            case ON_WITH_SIGNAL -> extractor.lastPowered;
            case OFF_WITH_SIGNAL -> !extractor.lastPowered;
            case PULSE -> false;
        };
        return allowed ? extractor : null;
    }

    /** Step 109: the pipe's network with its destinations sorted, the same way runExtractor gets it. Null if not ready. */
    @Nullable
    private PipeNetwork pushNetwork(Extractor extractor) {
        BlockState state = extractor.cachedState;
        PipeNetwork network = extractor.sortedFor;
        if (state == null || network == null || !network.valid) {
            if (!level.isLoaded(extractor.pos)) {
                return null;
            }
            state = level.getBlockState(extractor.pos);
            network = state.getBlock() instanceof FlowPipeBlock ? getOrBuild(extractor.pos) : null;
            extractor.cachedState = network != null ? state : null;
            if (network == null) {
                return null;
            }
        }
        if (extractor.sortedFor != network) {
            long bindStart = System.nanoTime();
            extractor.bindTo(network);
            peakBinds++;
            peakBindNanos += System.nanoTime() - bindStart;
        }
        return network;
    }

    /** True if this pull side's Keep in source is paused because its source is too big to count (for the screen). */
    public boolean isKeepPaused(BlockPos pos, Direction side, int resource) {
        Extractor extractor = extractorsByPos.get(pos.asLong());
        return extractor != null && (extractor.keepPausedMask & Extractor.statusBit(resource, side.get3DDataValue())) != 0;
    }

    /** True if this pull side's Fill destination to skipped a destination too big to count (for the screen). */
    public boolean isFillPaused(BlockPos pos, Direction side, int resource) {
        Extractor extractor = extractorsByPos.get(pos.asLong());
        return extractor != null && (extractor.fillPausedMask & Extractor.statusBit(resource, side.get3DDataValue())) != 0;
    }

    /**
     * True if this insert side's Max stock had to skip its inventory last time because it has too many slots to
     * count (for the Insert side screen). Only looks at an already built network; never builds one.
     */
    public boolean isStockPaused(BlockPos pos, Direction side, int resource) {
        PipeNetwork network = networkByPipe.get(pos.asLong());
        if (network == null || !network.valid) {
            return false;
        }
        for (int i = 0; i < network.endpoints.size(); i++) {
            PipeNetwork.Endpoint endpoint = network.endpoints.get(i);
            if (endpoint.side() == side && endpoint.pipePos().equals(pos)) {
                return endpoint.stockSkipped(resource);
            }
        }
        return false;
    }

    /**
     * Step 85 (Jade): for the energy pipe at pos, {best route tier, slowest route tier, machines reachable}, or null if
     * there is no energy network there. Uses the routes worked out for its pull sides when it has them; otherwise works
     * them out once (only while someone looks at it).
     */
    @Nullable
    public int[] routeSummary(BlockPos pos) {
        PipeNetwork network = getOrBuild(pos);
        if (network == null || !network.hasTiers()) {
            return null;
        }
        Extractor extractor = extractorsByPos.get(pos.asLong());
        byte[] tiers;
        int best;
        if (extractor != null && extractor.sortedFor == network) {
            tiers = extractor.routeTier;
            best = extractor.bestRouteTier;
        } else {
            tiers = new byte[network.endpointCount()];
            best = network.routeTiers(pos, tiers);
        }
        int slowest = Integer.MAX_VALUE;
        int machines = 0;
        for (int i = 0; i < network.endpointCount() && i < tiers.length; i++) {
            if (network.endpoints().get(i).extract() || tiers[i] < 0) {
                continue;
            }
            machines++;
            slowest = Math.min(slowest, tiers[i]);
        }
        return new int[]{best, machines == 0 ? best : slowest, machines};
    }

    public int extractorCount() {
        return extractors.size();
    }

    // ---------------------------------------------------------------- per-tick work budget

    /** Where next tick's loop starts. Pipes that didn't get a turn because the budget ran out go first. */
    private int extractorCursor;
    private boolean outOfBudget;
    private int tickCalls;
    private int callBudget;
    /** Read from the config once per tick. */
    private int maxCounted;
    /** Read from the config once per tick (step 38). */
    private int maxEmptyWait;
    /** Step 39: time spent re-sorting destinations this tick, and its limit (a quarter of the tick's time limit). */
    private long resortNanos;
    private long resortBudget;
    private long tickStartNanos;
    private long nanoBudget;

    // Stats over the current one-second window, published every 20 ticks for /daisychain stats.
    private int windowTicks;
    private long windowCalls;
    private long windowMaxNanos;
    private long windowTotalNanos;
    private int windowBudgetHits;
    private int windowMaxWaiting;
    private long lastSecondCalls;
    private long lastSecondMaxNanos;
    private long lastSecondTotalNanos;
    private int lastSecondBudgetHits;
    private int lastSecondMaxWaiting;

    // Step 37: peaks since the last /daisychain stats, to catch one-off costs such as rebuilding a big network after a
    // pipe is broken or placed (they would be missed by the one-second window if stats is run a few seconds later).
    private long peakTickNanos;
    private int peakBuilds;
    private long peakBuildNanos;
    private int peakBinds;
    private long peakBindNanos;

    /**
     * Runs once per server tick for this dimension. Only pulling pipes with a side that is due do any work.
     * All pipes share one budget per tick (inventory calls and time). When it runs out, the rest wait one tick
     * and go first next time, so every pipe still gets its turn, just slower.
     */
    public void tick() {
        sideRecheck.tick();
        if (extractors.isEmpty()) {
            recordTick(0, 0L, false, 0);
            return;
        }
        tickStartNanos = System.nanoTime();
        long now = level.getGameTime();
        int maxSlots = DaisychainConfig.MAX_SOURCE_SLOTS_SCANNED.get();
        int maxCalls = DaisychainConfig.MAX_HANDLER_CALLS_PER_TRANSFER.get();
        int maxBackoff = DaisychainConfig.MAX_IDLE_BACKOFF.get();
        maxCounted = DaisychainConfig.MAX_SLOTS_COUNTED.get();
        maxEmptyWait = DaisychainConfig.MAX_EMPTY_WAIT_TICKS.get();
        callBudget = DaisychainConfig.MAX_HANDLER_CALLS_PER_TICK.get();
        nanoBudget = (long) (DaisychainConfig.MAX_MILLISECONDS_PER_TICK.get() * 1_000_000.0);
        tickCalls = 0;
        outOfBudget = false;
        resortNanos = 0;
        resortBudget = nanoBudget / 4;

        int size = extractors.size();
        int first = extractorCursor < size ? extractorCursor : 0;
        int waiting = 0;
        for (int n = 0; n < size; n++) {
            int i = first + n;
            if (i >= size) {
                i -= size;
            }
            Extractor extractor = extractors.get(i);
            if (now < extractor.nextRun) {
                continue;
            }
            if (!outOfBudget && budgetSpent()) {
                outOfBudget = true;
            }
            if (outOfBudget) {
                if (waiting == 0) {
                    extractorCursor = i; // first pipe that didn't get its turn goes first next tick
                }
                waiting++;
                continue; // keep counting for stats; nothing else is done
            }
            runExtractor(extractor, now, maxSlots, maxCalls, maxBackoff);
            if (outOfBudget && waiting == 0) {
                // Ran out partway through this pipe: its remaining sides go first next tick.
                extractorCursor = i;
                waiting++;
            }
        }
        recordTick(tickCalls, System.nanoTime() - tickStartNanos, outOfBudget, waiting);
    }

    private boolean budgetSpent() {
        return tickCalls >= callBudget || System.nanoTime() - tickStartNanos >= nanoBudget;
    }

    private void recordTick(int calls, long nanos, boolean hitBudget, int waiting) {
        if (nanos > peakTickNanos) {
            peakTickNanos = nanos;
        }
        windowCalls += calls;
        windowTotalNanos += nanos;
        if (nanos > windowMaxNanos) {
            windowMaxNanos = nanos;
        }
        if (hitBudget) {
            windowBudgetHits++;
        }
        if (waiting > windowMaxWaiting) {
            windowMaxWaiting = waiting;
        }
        if (++windowTicks >= 20) {
            lastSecondCalls = windowCalls;
            lastSecondMaxNanos = windowMaxNanos;
            lastSecondTotalNanos = windowTotalNanos;
            lastSecondBudgetHits = windowBudgetHits;
            lastSecondMaxWaiting = windowMaxWaiting;
            windowTicks = 0;
            windowCalls = 0;
            windowMaxNanos = 0;
            windowTotalNanos = 0;
            windowBudgetHits = 0;
            windowMaxWaiting = 0;
        }
    }

    /** Average time per tick spent on pipe transfers in this dimension during the last full second, in nanoseconds. */
    public long lastSecondAverageNanos() {
        return lastSecondTotalNanos / 20;
    }

    /** Inventory calls made by all pulling pipes in this dimension during the last full second. */
    public long lastSecondCalls() {
        return lastSecondCalls;
    }

    /** Longest single tick of item transfers during the last full second, in nanoseconds. */
    public long lastSecondMaxNanos() {
        return lastSecondMaxNanos;
    }

    /** Slowest tick of pipe work since the last resetPeaks(), in nanoseconds. */
    public long peakTickNanos() {
        return peakTickNanos;
    }

    /** Networks built (or rebuilt) since the last resetPeaks(). */
    public int peakBuilds() {
        return peakBuilds;
    }

    /** Slowest single network build since the last resetPeaks(), in nanoseconds. */
    public long peakBuildNanos() {
        return peakBuildNanos;
    }

    /** Times a pulling pipe re-sorted its destinations (after its network was rebuilt) since the last resetPeaks(). */
    public int peakBinds() {
        return peakBinds;
    }

    /** Total time spent re-sorting destinations since the last resetPeaks(), in nanoseconds. */
    public long peakBindNanos() {
        return peakBindNanos;
    }

    /** Starts a new peak window (called by /daisychain stats after showing the peaks). */
    public void resetPeaks() {
        peakTickNanos = 0;
        peakBuilds = 0;
        peakBuildNanos = 0;
        peakBinds = 0;
        peakBindNanos = 0;
    }

    /** Ticks (out of 20) in the last full second where the budget ran out. */
    public int lastSecondBudgetHits() {
        return lastSecondBudgetHits;
    }

    /** Most pulling pipes left waiting for the next tick, during the last full second. */
    public int lastSecondMaxWaiting() {
        return lastSecondMaxWaiting;
    }

    /**
     * One transfer of one resource from one pull side, with that resource's settings. Returns the amount moved (items,
     * mB or FE); extractor.calls holds the handler calls it made.
     */
    private int transfer(Extractor extractor, Direction direction, int resource, int tier, PipeDataBlockEntity data,
                         int maxSlots, int maxCalls) {
        extractor.busy = true; // step 109: a push into this pipe from inside this transfer is refused
        try {
            return transferNow(extractor, direction, resource, tier, data, maxSlots, maxCalls);
        } finally {
            extractor.busy = false;
        }
    }

    private int transferNow(Extractor extractor, Direction direction, int resource, int tier, PipeDataBlockEntity data,
                            int maxSlots, int maxCalls) {
        if (resource == PipeKind.FLUIDS) {
            return FluidTransfer.transfer(level, extractor, direction, extractor.destinations,
                    data.distribution(direction), data.allowsSelfFeed(direction),
                    data.activeFilter(direction, resource), data.filterDenies(direction, resource),
                    UpgradeSpeeds.fluidMb(tier), data.batch(direction, resource),
                    data.keepInSource(direction, resource), data.fillDestinationTo(direction, resource),
                    maxCounted, maxSlots, maxCalls);
        }
        if (resource == PipeKind.ENERGY_RES) {
            // Step 109: what this side pulls and what blocks push into it share the side's speed.
            int side = direction.get3DDataValue();
            int perTransfer = UpgradeSpeeds.energyFe(tier);
            int batch = data.batch(direction, resource);
            if (batch > 0) {
                perTransfer = Math.min(perTransfer, batch);
            }
            int allowance = extractor.energyAllowance(side, level.getGameTime(), UpgradeSpeeds.energyInterval(tier),
                    perTransfer);
            int moved = EnergyTransfer.transfer(level, extractor, direction, extractor.destinations,
                    data.distribution(direction), data.allowsSelfFeed(direction),
                    allowance, data.batch(direction, resource),
                    data.keepInSource(direction, resource), data.fillDestinationTo(direction, resource), maxCalls);
            extractor.addEnergyUsed(side, moved);
            return moved;
        }
        return ItemTransfer.transfer(level, extractor, direction, extractor.destinations,
                data.distribution(direction), data.allowsSelfFeed(direction),
                data.activeFilter(direction, resource), data.filterDenies(direction, resource),
                UpgradeSpeeds.items(tier), data.batch(direction, resource),
                data.keepInSource(direction, resource), data.fillDestinationTo(direction, resource),
                maxCounted, maxSlots, maxCalls);
    }

    /**
     * Runs every pull side of one pipe that is due and that its redstone setting allows. Each side uses its own
     * upgrade's speed and keeps its own schedule; afterwards extractor.nextRun is set to the earliest side.
     * If the tick budget runs out partway, the remaining due sides stay due and are tried first next tick.
     */
    private void runExtractor(Extractor extractor, long now, int maxSlots, int maxCalls, int maxBackoff) {
        PipeDataBlockEntity data = extractor.data;
        // Step 36: the pipe's block state and network are remembered between runs, and only looked up again when the
        // pipe changes (onPipeChanged / setExtractor clear cachedState) or the network was invalidated. Saves a chunk
        // lookup and a hash map lookup per run.
        BlockState state = extractor.cachedState;
        PipeNetwork network = extractor.sortedFor;
        if (state == null || network == null || !network.valid) {
            state = level.getBlockState(extractor.pos);
            network = data != null && state.getBlock() instanceof FlowPipeBlock ? getOrBuild(extractor.pos) : null;
            extractor.cachedState = network != null ? state : null;
        }
        if (network == null || data == null) {
            // Not ready (block entity missing or network unavailable): look again after the normal interval.
            extractor.nextRun = now + UpgradeSpeeds.interval(extractor.kind, 0);
            return;
        }
        if (extractor.sortedFor != network) {
            if (resortNanos >= resortBudget) {
                // Step 39: re-sorting gets at most a quarter of the tick's time limit, so after a change in a huge
                // network only that network's pipes wait (a few more ticks); every other network keeps running.
                extractor.nextRun = now + 1;
                return;
            }
            // Only after a rebuild, so timing it here costs nothing in normal running.
            long bindStart = System.nanoTime();
            extractor.bindTo(network);
            long bindTime = System.nanoTime() - bindStart;
            resortNanos += bindTime;
            peakBinds++;
            peakBindNanos += bindTime;
        }
        if (!extractor.poweredKnown && signalReadable(extractor.pos)) {
            extractor.lastPowered = level.hasNeighborSignal(extractor.pos);
            extractor.poweredKnown = true;
        }
        boolean powered = extractor.lastPowered;

        long next = Long.MAX_VALUE;
        int firstSide = extractor.sideCursor;
        boolean cursorMoved = false;
        for (int k = 0; k < 6; k++) {
            int side = firstSide + k;
            if (side >= 6) {
                side -= 6;
            }
            Direction direction = Direction.from3DDataValue(side);
            int bit = 1 << side;
            if ((extractor.extractMask & bit) == 0
                    || state.getValue(FlowPipeBlock.propertyFor(direction)) != SideState.EXTRACT) {
                extractor.sideNextRun[side] = 0;
                continue;
            }
            if (now < extractor.sideNextRun[side]) {
                next = Math.min(next, extractor.sideNextRun[side]);
                continue;
            }
            RedstoneMode mode = data.redstoneMode(direction);
            boolean active = switch (mode) {
                case ALWAYS -> true;
                case ON_WITH_SIGNAL -> powered;
                case OFF_WITH_SIGNAL -> !powered;
                case PULSE -> (extractor.pendingPulse & bit) != 0;
            };
            if (!active) {
                // Can't run until the redstone signal changes: sleep (costs nothing until then).
                extractor.sideNextRun[side] = Long.MAX_VALUE;
                extractor.setStatus(side, mode == RedstoneMode.PULSE ? SideStatus.WAITING_FOR_PULSE
                        : SideStatus.REDSTONE_OFF);
                continue;
            }
            if (!outOfBudget && budgetSpent()) {
                outOfBudget = true;
            }
            if (outOfBudget) {
                // Out of budget for this tick: stay due, and this side goes first next tick.
                if (!cursorMoved) {
                    extractor.sideCursor = side;
                    cursorMoved = true;
                }
                next = Math.min(next, extractor.sideNextRun[side]);
                continue;
            }
            extractor.pendingPulse &= ~bit;

            int tier = extractor.speedTier(data, direction);
            int interval = UpgradeSpeeds.interval(extractor.kind, tier);
            boolean wasWoken = extractor.sideNextRun[side] == 0;
            extractor.sawItems = false;
            extractor.sideLastRun[side] = now;
            int moved = 0;
            int calls = 0;
            boolean noDestination = extractor.destinations.isEmpty();
            if (extractor.kind == PipeKind.UNIVERSAL) {
                // Universal pipe (step 33): items, fluids and energy, each at its own speed. The side wakes as often
                // as its fastest resource; a resource only runs once its own interval has passed.
                boolean saw = false;
                for (int resource = 0; resource < PipeKind.RESOURCE_COUNT; resource++) {
                    int slot = resource * 6 + side;
                    if (!data.pullsResource(direction, resource) || now < extractor.resourceLastRun[slot]
                            + UpgradeSpeeds.interval(PipeKind.ofResource(resource), tier)) {
                        continue; // switched off on this side (step 33a), or not due yet
                    }
                    extractor.resourceLastRun[slot] = now;
                    extractor.sawItems = false;
                    int movedNow = transfer(extractor, direction, resource, tier, data, maxSlots, maxCalls);
                    calls += extractor.calls;
                    extractor.sideStatus[slot] = (byte) status(movedNow, noDestination, extractor.sawItems);
                    saw |= extractor.sawItems;
                    moved += movedNow;
                }
                extractor.sawItems = saw; // any resource had something to give (backoff below)
            } else if (data.pullsResource(direction, extractor.kind.resource())) {
                moved = transfer(extractor, direction, extractor.kind.resource(), tier, data, maxSlots, maxCalls);
                calls = extractor.calls;
                extractor.sideStatus[extractor.kind.resource() * 6 + side] =
                        (byte) status(moved, noDestination, extractor.sawItems);
            }
            tickCalls += Math.max(1, calls);
            if (moved == 0 && !extractor.sawItems && level.isLoaded(extractor.pos.relative(direction))
                    && !extractor.hasAnySource(level, direction)) {
                // Nothing next to this pull side to pull from (step 34: pull sides no longer need a block there):
                // sleep at zero cost until a neighbor or its capability changes (Extractor.wakeIdleSides).
                extractor.idleMask |= bit;
                extractor.sideNextRun[side] = Long.MAX_VALUE;
                extractor.setStatus(side, SideStatus.NO_SOURCE);
                continue;
            }
            extractor.idleMask &= ~bit;
            if (moved > 0) {
                extractor.sideBackoff[side] = 1;
            } else if (!extractor.sawItems) {
                // Step 38: nothing to pull (empty source, or nothing the filter wants). Each check is cheap, but
                // thousands of idle pipes add up and used the whole per-tick call limit, making busy pipes wait. So
                // check less often, but never wait longer than max_empty_wait_ticks (or the normal interval if that is
                // longer); items that show up are picked up within that time.
                int cap = Math.max(1, maxEmptyWait / interval);
                extractor.sideBackoff[side] = Math.min(cap, extractor.sideBackoff[side] * 2);
            } else if (extractor.sideBackoff[side] < maxBackoff) {
                // Items are waiting but every destination refused them: this is the expensive case, so slow down.
                extractor.sideBackoff[side] = Math.min(maxBackoff, extractor.sideBackoff[side] * 2);
            }
            // Spread pipes over the interval so they don't all run on the same tick.
            long offset = wasWoken ? Math.floorMod(HashCommon.mix(extractor.pos.asLong() + side), interval) : 0;
            long sideNext = now + (long) interval * extractor.sideBackoff[side] + offset;
            extractor.sideNextRun[side] = sideNext;
            next = Math.min(next, sideNext);
        }
        if (!cursorMoved) {
            extractor.sideCursor = 0;
        }
        extractor.nextRun = next;
    }

    public static void remove(ServerLevel level) {
        MANAGERS.remove(level);
    }

    public static void clearAll() {
        MANAGERS.clear();
        PENDING_CHUNK_EVENTS.clear();
        PENDING_CHUNK_SCANS.clear();
    }

    /**
     * A chunk loaded or unloaded. Any network in it, or waiting at its edge, is dropped and
     * rebuilt the next time it is needed.
     */
    public static void onChunkEvent(ServerLevel level, long chunkPos) {
        if (level.getServer().isSameThread()) {
            NetworkManager manager = MANAGERS.get(level);
            if (manager != null) {
                manager.onChunkChanged(chunkPos);
            }
        } else {
            PENDING_CHUNK_EVENTS.add(new PendingChunkEvent(level, chunkPos));
        }
    }

    /** F40: a chunk loaded; queue it for the block-entity scan (always done at the next server tick start). */
    public static void onChunkLoadedForScan(ServerLevel level, long chunkPos) {
        PENDING_CHUNK_SCANS.add(new PendingChunkEvent(level, chunkPos));
    }

    /**
     * F40: every pipe of ours touching a block entity in the chunk (or across its edge) re-checks its arms next tick.
     * That puts sides still waiting for a block entity back on the SideRecheck list after a restart, and fixes arms
     * saved before F39/F40. Only block entities are looked at, never every block.
     */
    private static void scanLoadedChunk(ServerLevel level, long chunkPos) {
        int chunkX = ChunkPos.getX(chunkPos);
        int chunkZ = ChunkPos.getZ(chunkPos);
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            if (!(entry.getValue() instanceof PipeDataBlockEntity)) {
                scheduleRechecksAround(level, entry.getKey());
            }
        }
        scanEdge(level, chunkX - 1, chunkZ, chunkX * 16 - 1, Integer.MIN_VALUE);
        scanEdge(level, chunkX + 1, chunkZ, chunkX * 16 + 16, Integer.MIN_VALUE);
        scanEdge(level, chunkX, chunkZ - 1, Integer.MIN_VALUE, chunkZ * 16 - 1);
        scanEdge(level, chunkX, chunkZ + 1, Integer.MIN_VALUE, chunkZ * 16 + 16);
    }

    /** Block entities of chunk (x, z) on the column edgeX or the row edgeZ (MIN_VALUE = any). */
    private static void scanEdge(ServerLevel level, int chunkX, int chunkZ, int edgeX, int edgeZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            if ((edgeX == Integer.MIN_VALUE || pos.getX() == edgeX) && (edgeZ == Integer.MIN_VALUE || pos.getZ() == edgeZ)
                    && !(entry.getValue() instanceof PipeDataBlockEntity)) {
                scheduleRechecksAround(level, pos);
            }
        }
    }

    private static void scheduleRechecksAround(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos pipePos = pos.relative(direction);
            if (level.isLoaded(pipePos)) {
                BlockState state = level.getBlockState(pipePos);
                if (state.getBlock() instanceof FlowPipeBlock pipe) {
                    level.scheduleTick(pipePos, pipe, 1);
                }
            }
        }
    }

    /** Called at the start of every server tick. Does nothing unless chunk events were queued. */
    public static void processPendingChunkEvents() {
        if (!PENDING_CHUNK_SCANS.isEmpty()) {
            PendingChunkEvent scan;
            while ((scan = PENDING_CHUNK_SCANS.poll()) != null) {
                scanLoadedChunk(scan.level(), scan.chunkPos());
            }
        }
        if (PENDING_CHUNK_EVENTS.isEmpty()) {
            return;
        }
        PendingChunkEvent event;
        while ((event = PENDING_CHUNK_EVENTS.poll()) != null) {
            NetworkManager manager = MANAGERS.get(event.level());
            if (manager != null) {
                manager.onChunkChanged(event.chunkPos());
            }
        }
    }

    private void onChunkChanged(long chunkPos) {
        ReferenceArrayList<PipeNetwork> affected = networksByChunk.remove(chunkPos);
        if (affected == null) {
            return;
        }
        for (int i = 0; i < affected.size(); i++) {
            invalidate(affected.get(i));
        }
    }

    /** Called when a pipe is placed, removed, or changes its arms. */
    public void onPipeChanged(BlockPos pos) {
        long packed = pos.asLong();
        Extractor changed = extractorsByPos.get(packed);
        if (changed != null) {
            changed.cachedState = null; // its arms changed: read the block state again on its next run
        }
        invalidateAt(packed);
        for (Direction direction : DIRECTIONS) {
            invalidateAt(BlockPos.offset(packed, direction));
        }
    }

    public boolean isCached(BlockPos pos) {
        PipeNetwork network = networkByPipe.get(pos.asLong());
        return network != null && network.valid;
    }

    /** Returns the network containing the pipe at pos, building it if needed. Null if there is no loaded pipe there. */
    @Nullable
    public PipeNetwork getOrBuild(BlockPos pos) {
        PipeNetwork network = networkByPipe.get(pos.asLong());
        if (network != null && network.valid) {
            return network;
        }
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof FlowPipeBlock)) {
            return null;
        }
        return build(pos, state.getBlock());
    }

    public int networkCount() {
        return networks.size();
    }

    public int trackedPipeCount() {
        return networkByPipe.size();
    }

    public int trackedChunkCount() {
        return networksByChunk.size();
    }

    private void invalidateAt(long packedPos) {
        PipeNetwork network = networkByPipe.get(packedPos);
        if (network != null) {
            invalidate(network);
        }
    }

    private void invalidate(PipeNetwork network) {
        if (!network.valid) {
            return;
        }
        network.valid = false;
        LongArrayList pipes = network.pipes;
        for (int i = 0; i < pipes.size(); i++) {
            long pipe = pipes.getLong(i);
            if (networkByPipe.get(pipe) == network) {
                networkByPipe.remove(pipe);
            }
        }
        networks.remove(network);
        for (int i = 0; i < network.boundExtractors.size(); i++) {
            network.boundExtractors.get(i).wake();
        }
        network.boundExtractors.clear();
        removeFromChunkIndex(network, network.chunks);
        removeFromChunkIndex(network, network.missingChunks);
    }

    private void addToChunkIndex(PipeNetwork network, LongOpenHashSet chunkSet) {
        LongIterator iterator = chunkSet.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            ReferenceArrayList<PipeNetwork> list = networksByChunk.get(chunk);
            if (list == null) {
                list = new ReferenceArrayList<>(2);
                networksByChunk.put(chunk, list);
            }
            list.add(network);
        }
    }

    private void removeFromChunkIndex(PipeNetwork network, LongOpenHashSet chunkSet) {
        LongIterator iterator = chunkSet.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            ReferenceArrayList<PipeNetwork> list = networksByChunk.get(chunk);
            if (list != null) {
                list.remove(network);
                if (list.isEmpty()) {
                    networksByChunk.remove(chunk);
                }
            }
        }
    }

    /** Flood-fill from start over connected pipes of the same block. Stops at unloaded chunks. */
    private PipeNetwork build(BlockPos start, Block pipeBlock) {
        long startTime = System.nanoTime();
        PipeNetwork network = new PipeNetwork(pipeBlock);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        LongOpenHashSet visited = new LongOpenHashSet();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // Directions (bitmask) in which each pipe connects to another pipe of this network, by pipe index.
        ByteArrayList pipeLinks = new ByteArrayList();
        // Step 84: energy pipe tiers by pipe index, for route speeds (other pipe types have no tiers).
        boolean energy = pipeBlock instanceof EnergyPipeBlock;
        ByteArrayList pipeTiers = new ByteArrayList();
        Long2IntOpenHashMap indexByPipe = network.indexByPipe; // kept in the network (step 39)

        long startPacked = start.asLong();
        visited.add(startPacked);
        queue.enqueue(startPacked);

        while (!queue.isEmpty()) {
            long pipe = queue.dequeueLong();
            BlockState state = level.getBlockState(cursor.set(pipe));
            if (!FlowPipeBlock.sameNetwork(pipeBlock, state.getBlock())) {
                continue;
            }
            int pipeIndex = network.pipes.size();
            network.pipes.add(pipe);
            if (energy) {
                pipeTiers.add((byte) EnergyPipeBlock.tierOf(state.getBlock()));
            }
            indexByPipe.put(pipe, pipeIndex);
            int links = 0;
            network.chunks.add(ChunkPos.asLong(
                    SectionPos.blockToSectionCoord(BlockPos.getX(pipe)),
                    SectionPos.blockToSectionCoord(BlockPos.getZ(pipe))));
            PipeNetwork previous = networkByPipe.put(pipe, network);
            if (previous != null && previous != network) {
                // Should not happen, but never let two live networks share a pipe.
                invalidate(previous);
                networkByPipe.put(pipe, network);
            }

            for (Direction direction : DIRECTIONS) {
                SideState side = state.getValue(FlowPipeBlock.propertyFor(direction));
                if (side == SideState.NONE) {
                    continue;
                }
                long neighbor = BlockPos.offset(pipe, direction);
                int chunkX = SectionPos.blockToSectionCoord(BlockPos.getX(neighbor));
                int chunkZ = SectionPos.blockToSectionCoord(BlockPos.getZ(neighbor));
                if (!level.hasChunk(chunkX, chunkZ)) {
                    network.missingChunks.add(ChunkPos.asLong(chunkX, chunkZ));
                    continue;
                }
                BlockState neighborState = level.getBlockState(cursor.set(neighbor));
                if (FlowPipeBlock.sameNetwork(pipeBlock, neighborState.getBlock())) {
                    links |= 1 << direction.get3DDataValue();
                    if (visited.add(neighbor)) {
                        queue.enqueue(neighbor);
                    }
                } else {
                    PipeNetwork.Endpoint endpoint =
                            new PipeNetwork.Endpoint(BlockPos.of(pipe), pipeIndex, direction, side == SideState.EXTRACT);
                    if (side == SideState.CONNECTED && state.getValue(FlowPipeBlock.HAS_DATA)
                            && level.getBlockEntity(cursor.set(pipe)) instanceof PipeDataBlockEntity pipeData) {
                        endpoint.setInsertSettings(pipeData.priority(direction));
                        for (int resource = 0; resource < PipeKind.RESOURCE_COUNT; resource++) {
                            if (pipeData.carries(resource)) {
                                endpoint.setInsertSettings(resource, !pipeData.acceptsResource(direction, resource),
                                        pipeData.insertFilter(direction, resource),
                                        pipeData.insertFilterDenies(direction, resource),
                                        pipeData.maxStock(direction, resource));
                            }
                        }
                        RedstoneMode insertRedstone = pipeData.insertRedstoneMode(direction);
                        if (insertRedstone != RedstoneMode.ALWAYS) {
                            // Step 40: follows the pipe's signal; unknown (area not loaded) counts as no signal.
                            BlockPos pipePos = endpoint.pipePos();
                            endpoint.setRedstone(insertRedstone,
                                    signalReadable(pipePos) && level.hasNeighborSignal(pipePos));
                            network.redstoneEndpoints.add(endpoint);
                        }
                    }
                    endpoint.index = network.endpoints.size();
                    network.endpoints.add(endpoint);
                }
            }
            pipeLinks.add((byte) links);
        }

        // Turn the links into an index graph so path distances never need to read the world again.
        int count = network.pipes.size();
        int[] graph = new int[count * 6];
        java.util.Arrays.fill(graph, -1);
        for (int i = 0; i < count; i++) {
            int links = pipeLinks.getByte(i);
            if (links == 0) {
                continue;
            }
            long pipe = network.pipes.getLong(i);
            for (Direction direction : DIRECTIONS) {
                int d = direction.get3DDataValue();
                if ((links & (1 << d)) != 0) {
                    graph[i * 6 + d] = indexByPipe.get(BlockPos.offset(pipe, direction));
                }
            }
        }
        network.neighbors = graph;
        if (energy) {
            network.setPipeTiers(pipeTiers.toByteArray());
        }
        network.indexInsertEndpoints();

        network.buildNanos = System.nanoTime() - startTime;
        peakBuilds++;
        if (network.buildNanos > peakBuildNanos) {
            peakBuildNanos = network.buildNanos;
        }
        networks.add(network);
        addToChunkIndex(network, network.chunks);
        addToChunkIndex(network, network.missingChunks);
        return network;
    }

    private record PendingChunkEvent(ServerLevel level, long chunkPos) {
    }
}
