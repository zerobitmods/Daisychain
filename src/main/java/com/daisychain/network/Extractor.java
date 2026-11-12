package com.daisychain.network;

import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.block.PipeKind;
import com.daisychain.filter.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

/** Runtime state for one pipe that has at least one pulling side. Not saved. */
final class Extractor {
    final BlockPos pos;
    /** What the pipe carries; decides which transfer runs and which speeds apply. */
    PipeKind kind = PipeKind.ITEM;
    int extractMask;
    /** Index in NetworkManager's list, for O(1) removal. */
    int index = -1;
    /** Earliest tick any side is due (the minimum of sideNextRun). Long.MAX_VALUE = asleep until redstone changes. */
    long nextRun;
    /**
     * Each pull side keeps its own schedule, because each side can have a different upgrade (speed).
     * Indexed by Direction.get3DDataValue(). 0 = run as soon as possible, Long.MAX_VALUE = asleep.
     */
    final long[] sideNextRun = new long[6];
    final int[] sideBackoff = {1, 1, 1, 1, 1, 1};
    final long[] sideLastRun = {Long.MIN_VALUE / 2, Long.MIN_VALUE / 2, Long.MIN_VALUE / 2,
            Long.MIN_VALUE / 2, Long.MIN_VALUE / 2, Long.MIN_VALUE / 2};
    /** Set during a transfer when the source had items to offer (used to tell "empty" from "destinations full"). */
    boolean sawItems;
    /** The pipe's settings. Same lifetime as this extractor (it unregisters when removed). */
    @Nullable
    PipeDataBlockEntity data;
    boolean poweredKnown;
    boolean lastPowered;
    /** One bit per side: the redstone signal turned on since that side last ran (for pulse mode). */
    int pendingPulse;
    /**
     * One bit per pull side asleep because the block next to it offers nothing to pull (step 34): no work at all until
     * a neighbor changes (FlowPipeBlock.neighborChanged) or a neighbor's capability changes (the caches' listener).
     */
    int idleMask;
    /** Universal pipes: tick each resource of each side last ran (index = resource * 6 + side). */
    final long[] resourceLastRun = new long[PipeKind.RESOURCE_COUNT * 6];
    /** Round robin position per side per resource (index = resource * 6 + side). */
    final int[] roundRobin = new int[PipeKind.RESOURCE_COUNT * 6];
    /** Step 108: SideStatus per side per resource (index = resource * 6 + side), from the side's last run. */
    final byte[] sideStatus = new byte[PipeKind.RESOURCE_COUNT * 6];
    /** Calls into other blocks' inventories made by the current/last transfer (counts toward the tick budget). */
    int calls;
    /**
     * Side to try first next time. When the tick budget runs out partway through this pipe, the sides that
     * didn't get a turn go first next tick, so one busy side can't starve the others.
     */
    int sideCursor;
    /**
     * Item totals counted in the source during the current transfer (for "keep in source"), so each kind of item is
     * counted once per transfer. Reused every transfer; countSize = 0 empties it.
     */
    final Item[] countItems = new Item[16];
    final int[] countTotals = new int[16];
    int countSize;
    /** One bit per side: Keep in source is on but the source has too many slots to count, so that side is paused. */
    int keepPausedMask;

    /**
     * Bit for one resource of one side in keepPausedMask / fillPausedMask, and index base for roundRobin: each resource
     * of a side has its own (step 31), so a universal pipe's items don't overwrite its fluids' notices.
     */
    static int statusBit(int resource, int side) {
        return 1 << (resource * 6 + side);
    }
    /**
     * Item totals counted in destinations during the current transfer (for "fill destination to"): one entry per
     * (destination handler, item). Reused every transfer; fillSize = 0 empties it. Handlers are cleared after each
     * transfer so nothing is kept alive.
     */
    final IItemHandler[] fillTargets = new IItemHandler[16];
    final Item[] fillItems = new Item[16];
    final int[] fillTotals = new int[16];
    int fillSize;
    /** This transfer met a destination too big to count for "fill destination to" (it was skipped). */
    boolean fillSkipped;
    /** This transfer checked at least one destination for "fill destination to". */
    boolean fillChecked;
    /** One bit per side: Fill destination to skipped a destination last time because it was too big to count. */
    int fillPausedMask;
    /**
     * Fluid pipes (step 29): fluid totals counted in the source during the current transfer (Keep in source), and in
     * destinations (Fill destination to / Max stock), one entry per (destination handler, fluid). Same rules as the item
     * caches above; handler refs are cleared after each transfer. Totals are long because many tanks can add up.
     */
    final Fluid[] countFluids = new Fluid[16];
    final long[] countFluidTotals = new long[16];
    int countFluidSize;
    final IFluidHandler[] fluidFillTargets = new IFluidHandler[16];
    final Fluid[] fluidFillFluids = new Fluid[16];
    final long[] fluidFillTotals = new long[16];
    int fluidFillSize;
    /** Items moved this transfer per filter entry that has its own Batch (reused; cleared at each such transfer). */
    final int[] entryMoved = new int[ItemFilter.SIZE];

    /**
     * The pipe's block state as of its last run (step 36), or null to read it again. Cleared whenever the pipe's
     * state changes (NetworkManager.onPipeChanged, called from FlowPipeBlock.onPlace) or the extractor is set up.
     */
    @Nullable
    BlockState cachedState;
    /**
     * Review R6 (2026-11-09): destinations that have no room for an item during the current transfer (each one's slots
     * were all asked once), so the other source slots holding that item don't make the pipe walk them again. Reused
     * every transfer; refusedSize = 0 empties it, and the references are cleared after each transfer. (The step 36
     * scratch arrays that used to be here went with review R1: items are now committed one destination slot at a time.)
     */
    final IItemHandler[] refusedTargets = new IItemHandler[8];
    final ItemStack[] refusedItems = new ItemStack[8];
    int refusedSize;

    /** Destinations sorted nearest first, for the network they were computed from. */
    @Nullable
    PipeNetwork sortedFor;
    final ArrayList<PipeNetwork.Endpoint> destinations = new ArrayList<>();
    /** The same destinations for "farthest first": highest priority first, then farthest first within a priority. */
    final ArrayList<PipeNetwork.Endpoint> destinationsFarthest = new ArrayList<>();
    /**
     * Step 84, energy pipes: route tier per network endpoint (by Endpoint.index; -1 = can't be reached), the best of them
     * (what this pipe's pull sides run at), and the FE cap per route tier for the transfer in progress. Set by bindTo.
     */
    byte[] routeTier = new byte[0];
    int bestRouteTier;
    final int[] tierCap = new int[6];
    /** FE sent to each endpoint (by Endpoint.index) during the transfer in progress, so a route's cap holds per transfer. */
    int[] routeSent = new int[0];

    /**
     * Step 109: energy that other blocks push into a pull side (a Flux Point, cables, generators that push) shares that
     * side's speed with what the side pulls itself. Per side: start of the current speed window (game tick), FE used in
     * it, and the last tick anything was pushed in (the status shows Moving for a second after that).
     */
    final long[] energyWindowStart = {Long.MIN_VALUE / 2, Long.MIN_VALUE / 2, Long.MIN_VALUE / 2,
            Long.MIN_VALUE / 2, Long.MIN_VALUE / 2, Long.MIN_VALUE / 2};
    final int[] energyUsed = new int[6];
    final long[] energyPushTick = {Long.MIN_VALUE / 2, Long.MIN_VALUE / 2, Long.MIN_VALUE / 2,
            Long.MIN_VALUE / 2, Long.MIN_VALUE / 2, Long.MIN_VALUE / 2};
    /**
     * Step 109: true while this pipe is moving something. A block that pushes energy back into this pipe from inside
     * that move (directly or through other blocks) is refused, so the transfer state below is never used twice at once
     * and nothing can loop.
     */
    boolean busy;
    /** Step 116: what /daisychain lag measured for this pipe so far; null when no measurement runs (almost always). */
    @Nullable
    LagProbe.Sample lag;
    /** Step 109: stands in for the pushing block during a push (reused, no allocation). */
    final EnergyTransfer.PushSource pushSource = new EnergyTransfer.PushSource();

    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<IItemHandler, Direction>[] sourceCaches = new BlockCapabilityCache[6];
    final int[] slotCursor = new int[6];
    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<IFluidHandler, Direction>[] fluidSourceCaches = new BlockCapabilityCache[6];
    @SuppressWarnings("unchecked")
    private final BlockCapabilityCache<IEnergyStorage, Direction>[] energySourceCaches = new BlockCapabilityCache[6];

    /**
     * Review R4: one bit per side whose neighbor block is in another chunk than this pipe. Only those sides need a
     * "really loaded" check before their block is looked at; the pipe's own chunk is loaded while this object exists.
     */
    private final int crossChunkMask;
    /**
     * Decision 1 (zerobit, 2026-11-09): the pipe's chunk (as ChunkPos.asLong), to ask "do blocks tick here?" before
     * the pipe runs (NetworkManager.runExtractor).
     */
    final long chunkKey;

    Extractor(BlockPos pos) {
        this.pos = pos;
        this.chunkKey = ChunkPos.asLong(pos);
        int mask = 0;
        for (Direction side : Direction.values()) {
            if (((pos.getX() + side.getStepX()) >> 4) != (pos.getX() >> 4)
                    || ((pos.getZ() + side.getStepZ()) >> 4) != (pos.getZ() >> 4)) {
                mask |= 1 << side.get3DDataValue();
            }
        }
        this.crossChunkMask = mask;
    }

    /**
     * Review R4: false if the block on this side sits in a neighboring chunk that isn't really loaded yet. Asking for
     * its inventory then would make the server wait for that chunk; the side just tries again on its next turn.
     */
    private boolean neighborLoaded(ServerLevel level, Direction side) {
        return (crossChunkMask & (1 << side.get3DDataValue())) == 0
                || Loaded.chunk(level, (pos.getX() + side.getStepX()) >> 4, (pos.getZ() + side.getStepZ()) >> 4) != null;
    }

    @Nullable
    IItemHandler source(ServerLevel level, Direction side) {
        if (!neighborLoaded(level, side)) {
            return null;
        }
        int i = side.get3DDataValue();
        BlockCapabilityCache<IItemHandler, Direction> cache = sourceCaches[i];
        if (cache == null) {
            cache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, pos.relative(side), side.getOpposite(),
                    this::isRegistered, this::wakeIdleSides);
            sourceCaches[i] = cache;
        }
        return cache.getCapability();
    }

    /** The fluid handler of the block on this side (fluid pipes), cached. Null if it has none or isn't loaded. */
    @Nullable
    IFluidHandler fluidSource(ServerLevel level, Direction side) {
        if (!neighborLoaded(level, side)) {
            return null;
        }
        int i = side.get3DDataValue();
        BlockCapabilityCache<IFluidHandler, Direction> cache = fluidSourceCaches[i];
        if (cache == null) {
            cache = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, level, pos.relative(side),
                    side.getOpposite(), this::isRegistered, this::wakeIdleSides);
            fluidSourceCaches[i] = cache;
        }
        return cache.getCapability();
    }

    /** The energy storage of the block on this side (energy pipes), cached. Null if it has none or isn't loaded. */
    @Nullable
    IEnergyStorage energySource(ServerLevel level, Direction side) {
        if (!neighborLoaded(level, side)) {
            return null;
        }
        int i = side.get3DDataValue();
        BlockCapabilityCache<IEnergyStorage, Direction> cache = energySourceCaches[i];
        if (cache == null) {
            cache = BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, level, pos.relative(side),
                    side.getOpposite(), this::isRegistered, this::wakeIdleSides);
            energySourceCaches[i] = cache;
        }
        return cache.getCapability();
    }

    /** Caches stop reporting once this pipe no longer has pulling sides (removed from the manager). */
    private boolean isRegistered() {
        return index >= 0;
    }

    /**
     * True if the block on this side offers anything this pipe can pull (any of the three for universal pipes).
     * Only asked when a side moved nothing, to decide whether it may sleep. Each check is a cached lookup.
     */
    boolean hasAnySource(ServerLevel level, Direction side) {
        return switch (kind) {
            case FLUID -> fluidSource(level, side) != null;
            case ENERGY -> energySource(level, side) != null;
            case UNIVERSAL -> source(level, side) != null || fluidSource(level, side) != null
                    || energySource(level, side) != null;
            default -> source(level, side) != null;
        };
    }

    /**
     * Sides asleep because nothing was next to them (step 34): run them again soon. Called when a neighbor changes,
     * and by the source caches when NeoForge invalidates a neighbor's capability (not per tick). Cheap when no side is
     * idle.
     */
    void wakeIdleSides() {
        if (idleMask == 0) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            if ((idleMask & (1 << i)) != 0) {
                sideNextRun[i] = 0;
            }
        }
        idleMask = 0;
        nextRun = 0;
    }

    /**
     * Called only when the network changed, never per tick. Sorts destinations by distance along the
     * pipes (number of pipe steps), breaking ties by straight-line distance.
     */
    void bindTo(PipeNetwork network) {
        sortedFor = network;
        destinations.clear();
        network.orderDestinations(pos, destinations); // step 39: no per-pipe sort or garbage
        destinationsFarthest.clear();
        int start = 0;
        while (start < destinations.size()) {
            int end = start + 1;
            while (end < destinations.size() && destinations.get(end).priority() == destinations.get(start).priority()) {
                end++;
            }
            for (int i = end - 1; i >= start; i--) {
                destinationsFarthest.add(destinations.get(i));
            }
            start = end;
        }
        if (network.hasTiers()) {
            int endpointCount = network.endpointCount();
            if (routeTier.length < endpointCount) {
                routeTier = new byte[endpointCount];
                routeSent = new int[endpointCount];
            }
            bestRouteTier = network.routeTiers(pos, routeTier);
        } else {
            bestRouteTier = 0;
        }
        network.boundExtractors.add(this);
        Arrays.fill(sideBackoff, 1);
    }

    /**
     * The speed level a pull side runs at: its upgrade, or for energy pipes (step 84) the tier of its best route.
     */
    int speedTier(@Nullable PipeDataBlockEntity data, Direction side) {
        if (kind == PipeKind.ENERGY) {
            return sortedFor != null && sortedFor.valid ? bestRouteTier : 0;
        }
        return data != null ? data.upgradeLevel(side) : 0;
    }

    /**
     * Energy pipes: the most FE this transfer may still send to this destination, from the tier of the route to it
     * (tierCap, filled at the start of the transfer) minus what it already got. Integer.MAX_VALUE where routes don't
     * apply (universal pipes).
     */
    int routeCap(PipeNetwork.Endpoint destination) {
        if (kind != PipeKind.ENERGY) {
            return Integer.MAX_VALUE;
        }
        int index = destination.index;
        int tier = index >= 0 && index < routeTier.length ? routeTier[index] : -1;
        return tier < 0 ? 0 : Math.max(0, tierCap[tier] - routeSent[index]);
    }

    /** Energy pipes: counts FE that reached this destination in the transfer in progress (see routeCap). */
    void addRouteSent(PipeNetwork.Endpoint destination, int amount) {
        int index = destination.index;
        if (kind == PipeKind.ENERGY && index >= 0 && index < routeSent.length) {
            routeSent[index] += amount;
        }
    }

    /**
     * Step 109: FE this side may still move in its current speed window (perTransfer per interval ticks), pulled and
     * pushed in together. Starts a new window when the old one has passed.
     */
    int energyAllowance(int side, long now, int interval, int perTransfer) {
        if (now >= energyWindowStart[side] + Math.max(1, interval) || now < energyWindowStart[side]) {
            energyWindowStart[side] = now;
            energyUsed[side] = 0;
        }
        return Math.max(0, perTransfer - energyUsed[side]);
    }

    /** Step 109: counts FE moved by this side in its current speed window (never more than the window allows). */
    void addEnergyUsed(int side, int amount) {
        if (amount > 0) {
            energyUsed[side] = (int) Math.min(Integer.MAX_VALUE, (long) energyUsed[side] + amount);
        }
    }

    /** Step 108: sets the status of every resource of a side (redstone and "nothing attached" apply to all of them). */
    void setStatus(int side, int status) {
        for (int resource = 0; resource < PipeKind.RESOURCE_COUNT; resource++) {
            sideStatus[resource * 6 + side] = (byte) status;
        }
    }

    /** Settings, upgrades or sides changed: every side runs again on the next tick. */
    void wake() {
        idleMask = 0;
        nextRun = 0;
        Arrays.fill(sideNextRun, 0L);
        Arrays.fill(sideBackoff, 1);
        pendingPulse = 0;
    }
}
