package com.daisychain.network;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.daisychain.block.PipeKind;
import com.daisychain.block.RedstoneMode;
import com.daisychain.filter.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One group of connected pipes of the same type. Built once by NetworkManager and
 * thrown away whenever a pipe in it changes. Server-side only, never saved.
 */
public final class PipeNetwork {
    private static int nextId = 1;

    private final int id;
    private final Block pipeBlock;
    final LongArrayList pipes = new LongArrayList();
    /** In-memory copy of the pipe graph: for pipe i, neighbors[i * 6 + direction] is the connected pipe's index or -1. */
    int[] neighbors = new int[0];
    final List<Endpoint> endpoints = new ArrayList<>();
    /** Chunks this network has pipes in. */
    final LongOpenHashSet chunks = new LongOpenHashSet();
    /** Unloaded chunks the network continues into; it may be bigger than what we see. */
    final LongOpenHashSet missingChunks = new LongOpenHashSet();
    /** Step 40: insert endpoints with a redstone mode other than Always (usually none), updated on redstone changes. */
    final List<Endpoint> redstoneEndpoints = new ArrayList<>();
    /** Pulling pipes currently using this network; woken when it is invalidated. */
    final List<Extractor> boundExtractors = new ArrayList<>();
    long buildNanos;
    boolean valid = true;
    /** Step 39: pipe position (BlockPos.asLong) to its index in pipes, kept from the build. -1 if not in the network. */
    final Long2IntOpenHashMap indexByPipe = new Long2IntOpenHashMap();
    /**
     * Step 39: insert (non-pull) endpoints grouped by pipe, set by indexInsertEndpoints at the end of the build. Pipe i's
     * are endpoints.get(insertEndpoints[k]) for k from insertFirst[i] to insertFirst[i + 1] - 1, in endpoint order.
     */
    private int[] insertFirst = new int[1];
    private int[] insertEndpoints = new int[0];
    // Working arrays for orderDestinations, made on first use and shared by every pulling pipe of this network (the
    // server thread is the only user), so re-sorting after a rebuild creates no garbage per pipe.
    @Nullable
    private int[] visitStamp;
    private int stampCounter;
    private int[] searchQueue;
    private int[] searchDistance;
    private int[] orderEndpoint;
    private int[] orderDistance;
    private long[] orderKey;
    /** Step 84: energy pipe tier by pipe index (null for other pipe types), and which tiers occur (bit per tier). */
    @Nullable
    private byte[] pipeTiers;
    private int tierMask;
    /** Scratch for routeTiers: per pipe, the best route tier found so far (-1 = not reached), and a per-pass stamp. */
    private int[] routeBest;
    private int[] routeStamp;
    private int routeStampCounter;

    PipeNetwork(Block pipeBlock) {
        this.id = nextId++;
        this.pipeBlock = pipeBlock;
        indexByPipe.defaultReturnValue(-1);
    }

    /** Called once at the end of the build, after all pipes and endpoints are known. */
    void indexInsertEndpoints() {
        int count = pipes.size();
        int[] first = new int[count + 1];
        for (int j = 0; j < endpoints.size(); j++) {
            Endpoint endpoint = endpoints.get(j);
            if (!endpoint.extract()) {
                first[endpoint.pipeIndex + 1]++;
            }
        }
        for (int i = 0; i < count; i++) {
            first[i + 1] += first[i];
        }
        int[] list = new int[first[count]];
        int[] next = Arrays.copyOf(first, count);
        for (int j = 0; j < endpoints.size(); j++) {
            Endpoint endpoint = endpoints.get(j);
            if (!endpoint.extract()) {
                list[next[endpoint.pipeIndex]++] = j;
            }
        }
        insertFirst = first;
        insertEndpoints = list;
    }

    /**
     * Adds this network's destinations (insert endpoints) to out in "nearest first" order for a pulling pipe at from:
     * highest priority first; within a priority, fewest pipe steps first, then shortest straight-line distance, then the
     * order the build found them in. (Same order as before step 39, found faster: one breadth-first walk over the pipe
     * graph lists the destinations already sorted by pipe steps, so only groups at the same step count and, if
     * priorities differ, the priorities need sorting, done on plain numbers.) Called only after a network change.
     */
    void orderDestinations(BlockPos from, List<Endpoint> out) {
        int count = pipes.size();
        int destinationCount = insertEndpoints.length;
        if (destinationCount == 0) {
            return;
        }
        if (visitStamp == null) {
            visitStamp = new int[count];
            searchQueue = new int[count];
            searchDistance = new int[count];
            orderEndpoint = new int[destinationCount];
            orderDistance = new int[destinationCount];
            orderKey = new long[destinationCount];
        }
        // A pipe counts as visited when its stamp equals this walk's stamp, so the array never needs clearing.
        if (++stampCounter == Integer.MAX_VALUE) {
            Arrays.fill(visitStamp, 0);
            stampCounter = 1;
        }
        int stamp = stampCounter;

        int n = 0;
        int start = indexByPipe.get(from.asLong());
        if (start >= 0) {
            int head = 0;
            int tail = 0;
            searchQueue[tail++] = start;
            visitStamp[start] = stamp;
            searchDistance[start] = 0;
            while (head < tail) {
                int current = searchQueue[head++];
                int distance = searchDistance[current];
                for (int k = insertFirst[current]; k < insertFirst[current + 1]; k++) {
                    orderEndpoint[n] = insertEndpoints[k];
                    orderDistance[n] = distance;
                    n++;
                }
                int base = current * 6;
                for (int d = 0; d < 6; d++) {
                    int neighbor = neighbors[base + d];
                    if (neighbor >= 0 && visitStamp[neighbor] != stamp) {
                        visitStamp[neighbor] = stamp;
                        searchDistance[neighbor] = distance + 1;
                        searchQueue[tail++] = neighbor;
                    }
                }
            }
        }
        if (n < destinationCount) {
            // Not reachable from the pipe (shouldn't happen: a network is one connected group): last, in build order.
            for (int i = 0; i < count; i++) {
                if (visitStamp[i] == stamp) {
                    continue;
                }
                for (int k = insertFirst[i]; k < insertFirst[i + 1]; k++) {
                    orderEndpoint[n] = insertEndpoints[k];
                    orderDistance[n] = Integer.MAX_VALUE;
                    n++;
                }
            }
        }

        // Same pipe steps: shortest straight-line distance first, then build order (key = distance << 32 | index).
        int runStart = 0;
        while (runStart < n) {
            int runEnd = runStart + 1;
            while (runEnd < n && orderDistance[runEnd] == orderDistance[runStart]) {
                runEnd++;
            }
            if (runEnd - runStart > 1) {
                for (int i = runStart; i < runEnd; i++) {
                    int endpoint = orderEndpoint[i];
                    orderKey[i] = ((long) endpoints.get(endpoint).pipePos().distManhattan(from) << 32) | endpoint;
                }
                Arrays.sort(orderKey, runStart, runEnd);
                for (int i = runStart; i < runEnd; i++) {
                    orderEndpoint[i] = (int) orderKey[i];
                }
            }
            runStart = runEnd;
        }

        // Priorities: usually all equal, then the order above is final. Otherwise a stable sort by priority, highest
        // first (key = -priority << 32 | position in the order above).
        int firstPriority = endpoints.get(orderEndpoint[0]).priority();
        boolean mixed = false;
        for (int i = 1; i < n; i++) {
            if (endpoints.get(orderEndpoint[i]).priority() != firstPriority) {
                mixed = true;
                break;
            }
        }
        if (!mixed) {
            for (int i = 0; i < n; i++) {
                out.add(endpoints.get(orderEndpoint[i]));
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            orderKey[i] = (-(long) endpoints.get(orderEndpoint[i]).priority() << 32) | i;
        }
        Arrays.sort(orderKey, 0, n);
        for (int i = 0; i < n; i++) {
            out.add(endpoints.get(orderEndpoint[(int) orderKey[i]]));
        }
    }

    /** Step 84: called once at the end of an energy network's build. */
    void setPipeTiers(byte[] tiers) {
        pipeTiers = tiers;
        int mask = 0;
        for (byte tier : tiers) {
            mask |= 1 << tier;
        }
        tierMask = mask;
    }

    /** True for energy networks, whose pipes have tiers. */
    boolean hasTiers() {
        return pipeTiers != null;
    }

    /** The tier of the pipe at this index (energy networks only). */
    int pipeTier(int index) {
        return pipeTiers[index];
    }

    /**
     * Step 84 (zerobit): route speed. For a pull side on the pipe at from, writes into out (one entry per endpoint, by
     * Endpoint.index) the tier of the best route to that endpoint: the highest tier T such that the endpoint's pipe can
     * be reached from from through pipes of tier T or higher only (from itself included), or -1 if it can't be reached.
     * Returns the best of them (the start pipe's tier if no endpoint is reachable).
     * One breadth-first walk per tier that occurs in the network, highest first; a pipe keeps the first (highest) tier
     * that reaches it. Most networks have one tier, so this is one walk. Called only after a network change.
     */
    int routeTiers(BlockPos from, byte[] out) {
        int count = pipes.size();
        java.util.Arrays.fill(out, 0, endpoints.size(), (byte) -1);
        int start = indexByPipe.get(from.asLong());
        if (start < 0 || pipeTiers == null) {
            return 0;
        }
        if (routeBest == null || routeBest.length < count) {
            routeBest = new int[count];
            routeStamp = new int[count];
            routeStampCounter = 0;
        }
        if (searchQueue == null || searchQueue.length < count) {
            searchQueue = new int[count];
        }
        java.util.Arrays.fill(routeBest, 0, count, -1);
        int startTier = pipeTiers[start];
        for (int tier = startTier; tier >= 0; tier--) {
            if ((tierMask & (1 << tier)) == 0 && tier != startTier) {
                continue; // no pipe of this tier: the walk would be the same as the one above it
            }
            if (++routeStampCounter == Integer.MAX_VALUE) {
                java.util.Arrays.fill(routeStamp, 0);
                routeStampCounter = 1;
            }
            int stamp = routeStampCounter;
            int head = 0;
            int tail = 0;
            searchQueue[tail++] = start;
            routeStamp[start] = stamp;
            while (head < tail) {
                int current = searchQueue[head++];
                if (routeBest[current] < 0) {
                    routeBest[current] = tier;
                }
                int base = current * 6;
                for (int d = 0; d < 6; d++) {
                    int neighbor = neighbors[base + d];
                    if (neighbor >= 0 && routeStamp[neighbor] != stamp && pipeTiers[neighbor] >= tier) {
                        routeStamp[neighbor] = stamp;
                        searchQueue[tail++] = neighbor;
                    }
                }
            }
        }
        int best = -1;
        for (int j = 0; j < endpoints.size(); j++) {
            Endpoint endpoint = endpoints.get(j);
            if (endpoint.extract()) {
                continue;
            }
            int tier = routeBest[endpoint.pipeIndex];
            out[j] = (byte) tier;
            best = Math.max(best, tier);
        }
        return best < 0 ? startTier : best;
    }

    public int id() {
        return id;
    }

    public Block pipeBlock() {
        return pipeBlock;
    }

    public int pipeCount() {
        return pipes.size();
    }

    /** The position (BlockPos.asLong) of the pipe at this index, 0 to pipeCount() - 1. */
    public long pipeAt(int index) {
        return pipes.getLong(index);
    }

    public int endpointCount() {
        return endpoints.size();
    }

    public int extractCount() {
        int count = 0;
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).extract()) {
                count++;
            }
        }
        return count;
    }

    public List<Endpoint> endpoints() {
        return endpoints;
    }

    public boolean isComplete() {
        return missingChunks.isEmpty();
    }

    public boolean isValid() {
        return valid;
    }

    public long buildNanos() {
        return buildNanos;
    }

    /**
     * A place where the network touches a non-pipe block: the pipe at pipePos has an arm pointing to side.
     * extract is true when that side is set to pull from the block.
     */
    public static final class Endpoint {
        private final BlockPos pipePos;
        private final Direction side;
        private final boolean extract;
        private final BlockPos targetPos;
        /** Review R4: the target is in another chunk than the pipe. */
        private final boolean crossChunk;
        /** Index of pipePos in the network's pipe list. */
        final int pipeIndex;
        /** Index of this endpoint in the network's endpoint list (step 84: per-destination route speeds). */
        int index;
        @Nullable
        private BlockCapabilityCache<IItemHandler, Direction> itemCache;
        @Nullable
        private BlockCapabilityCache<IFluidHandler, Direction> fluidCache;
        @Nullable
        private BlockCapabilityCache<IEnergyStorage, Direction> energyCache;
        /**
         * Insert side settings, read from the pipe when the network is built (a change rebuilds the network). Priority
         * is shared; filter, filter mode and Max stock are per resource (index PipeKind.ITEMS / FLUIDS / ENERGY_RES).
         */
        private int priority;
        private final ItemFilter[] insertFilters = new ItemFilter[PipeKind.RESOURCE_COUNT];
        private int insertDenyMask;
        /** One bit per resource (step 33a): this destination accepts none of that resource. */
        private int insertOffMask;
        /** Insert side Max stock per resource for the whole side (0 = off); insert filter entries can set their own. */
        private final int[] maxStock = new int[PipeKind.RESOURCE_COUNT];
        /** Set by transfers, one bit per resource: Max stock had to skip this destination (too big to count). */
        private int stockSkippedMask;
        /** Insert side redstone (step 40): the mode, and whether the current signal stops this destination. */
        private RedstoneMode redstoneMode = RedstoneMode.ALWAYS;
        private boolean redstoneBlocked;
        /** Review R5: the signal couldn't be read when the network was built (area not loaded): closed until it can. */
        private boolean redstoneUnknown;

        Endpoint(BlockPos pipePos, int pipeIndex, Direction side, boolean extract) {
            this.pipePos = pipePos;
            this.pipeIndex = pipeIndex;
            this.side = side;
            this.extract = extract;
            this.targetPos = pipePos.relative(side);
            this.crossChunk = (targetPos.getX() >> 4) != (pipePos.getX() >> 4)
                    || (targetPos.getZ() >> 4) != (pipePos.getZ() >> 4);
        }

        /**
         * Review R4: false if the target block sits in a neighboring chunk that isn't really loaded (only checked for
         * targets across a chunk border). Looking up its inventory then would make the server wait for that chunk.
         */
        private boolean targetLoaded(ServerLevel level) {
            return !crossChunk || Loaded.isLoaded(level, targetPos);
        }

        public BlockPos pipePos() {
            return pipePos;
        }

        public Direction side() {
            return side;
        }

        public boolean extract() {
            return extract;
        }

        public BlockPos targetPos() {
            return targetPos;
        }

        void setInsertSettings(int priority) {
            this.priority = priority;
        }

        void setInsertSettings(int resource, boolean off, @Nullable ItemFilter insertFilter, boolean insertDeny,
                               int maxStock) {
            this.insertOffMask = off ? insertOffMask | (1 << resource) : insertOffMask & ~(1 << resource);
            this.insertFilters[resource] = insertFilter;
            this.insertDenyMask = insertDeny ? insertDenyMask | (1 << resource) : insertDenyMask & ~(1 << resource);
            this.maxStock[resource] = maxStock;
        }

        void setRedstone(RedstoneMode mode, boolean powered) {
            redstoneMode = mode;
            setPowered(powered);
        }

        /** The pipe's redstone signal changed: On with signal accepts only while powered, Off with signal only while not. */
        void setPowered(boolean powered) {
            redstoneUnknown = false;
            redstoneBlocked = switch (redstoneMode) {
                case ON_WITH_SIGNAL -> !powered;
                case OFF_WITH_SIGNAL -> powered;
                default -> false;
            };
        }

        /**
         * Review R5: this insert side follows redstone, but the area around its pipe isn't loaded far enough to read
         * the signal. It accepts nothing until the signal can be read (NetworkManager.retryUnknownRedstone, or the
         * next neighbor update), instead of guessing "no signal".
         */
        void setRedstoneUnknown(RedstoneMode mode) {
            redstoneMode = mode;
            redstoneUnknown = true;
            redstoneBlocked = true;
        }

        boolean redstoneUnknown() {
            return redstoneUnknown;
        }

        /** True while redstone stops this destination from accepting anything. */
        public boolean redstoneBlocked() {
            return redstoneBlocked;
        }

        boolean stockSkipped(int resource) {
            return (stockSkippedMask & (1 << resource)) != 0;
        }

        void setStockSkipped(int resource, boolean skipped) {
            stockSkippedMask = skipped ? stockSkippedMask | (1 << resource) : stockSkippedMask & ~(1 << resource);
        }

        private boolean denies(int resource) {
            return (insertDenyMask & (1 << resource)) != 0;
        }

        /**
         * Max stock for this item (0 = no limit): the most exact insert filter entry's own value if it has one
         * (Allow mode only, same rule as pull filters), else the side's. No allocation.
         */
        public int maxStockFor(ItemStack stack) {
            ItemFilter filter = insertFilters[PipeKind.ITEMS];
            if (filter != null && !denies(PipeKind.ITEMS) && filter.hasFillOverride()) {
                int entry = filter.entryFor(stack);
                if (entry >= 0) {
                    int own = filter.number(entry, ItemFilter.FILL);
                    if (own != ItemFilter.UNSET) {
                        return own;
                    }
                }
            }
            return maxStock[PipeKind.ITEMS];
        }

        /**
         * Fluid pipes: Max stock for this fluid in mB (0 = no limit): the most exact insert filter entry's own value if it
         * has one (Allow mode only), else the side's. No allocation.
         */
        public int maxStockForFluid(FluidStack stack) {
            ItemFilter filter = insertFilters[PipeKind.FLUIDS];
            if (filter != null && !denies(PipeKind.FLUIDS) && filter.hasFillOverride()) {
                int entry = filter.entryForFluid(stack);
                if (entry >= 0) {
                    int own = filter.number(entry, ItemFilter.FILL);
                    if (own != ItemFilter.UNSET) {
                        return own;
                    }
                }
            }
            return maxStock[PipeKind.FLUIDS];
        }

        /** Energy: false if this insert side is switched off for energy (step 33a). */
        public boolean acceptsEnergy() {
            return !redstoneBlocked && (insertOffMask & (1 << PipeKind.ENERGY_RES)) == 0;
        }

        /** Energy pipes: Max stock in FE (0 = no limit). Energy has no filter, so it is always the side's value. */
        public int maxStockForEnergy() {
            return maxStock[PipeKind.ENERGY_RES];
        }

        /** Higher priority destinations get items first. */
        public int priority() {
            return priority;
        }

        /**
         * True if this destination's insert filter lets the item in (always true without one). The filter object is
         * the pipe's own, so edits to its entries apply at once; no allocation.
         */
        public boolean accepts(ItemStack stack) {
            if (redstoneBlocked || (insertOffMask & (1 << PipeKind.ITEMS)) != 0) {
                return false;
            }
            ItemFilter filter = insertFilters[PipeKind.ITEMS];
            return filter == null || !filter.isActive() || filter.test(stack, denies(PipeKind.ITEMS));
        }

        /** Fluid pipes: true if this destination's insert filter lets the fluid in (always without one). */
        public boolean acceptsFluid(FluidStack stack) {
            if (redstoneBlocked || (insertOffMask & (1 << PipeKind.FLUIDS)) != 0) {
                return false;
            }
            ItemFilter filter = insertFilters[PipeKind.FLUIDS];
            return filter == null || !filter.isActive() || filter.testFluid(stack, denies(PipeKind.FLUIDS));
        }

        /** The face of the target block that touches the pipe. */
        public Direction targetSide() {
            return side.getOpposite();
        }

        /** The target's item handler, cached. Null if it has none or its chunk isn't loaded. */
        @Nullable
        public IItemHandler itemHandler(ServerLevel level) {
            if (!targetLoaded(level)) {
                return null;
            }
            if (itemCache == null) {
                itemCache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, targetPos, side.getOpposite());
            }
            return itemCache.getCapability();
        }

        /** The target's energy storage (energy pipes), cached. Null if it has none or its chunk isn't loaded. */
        @Nullable
        public IEnergyStorage energyHandler(ServerLevel level) {
            if (!targetLoaded(level)) {
                return null;
            }
            if (energyCache == null) {
                energyCache = BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, level, targetPos,
                        side.getOpposite());
            }
            return energyCache.getCapability();
        }

        /** The target's fluid handler (fluid pipes), cached. Null if it has none or its chunk isn't loaded. */
        @Nullable
        public IFluidHandler fluidHandler(ServerLevel level) {
            if (!targetLoaded(level)) {
                return null;
            }
            if (fluidCache == null) {
                fluidCache = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, level, targetPos,
                        side.getOpposite());
            }
            return fluidCache.getCapability();
        }
    }
}
