package com.daisychain.network;

import com.daisychain.Compat;
import com.daisychain.Daisychain;
import com.daisychain.block.Distribution;
import com.daisychain.block.PipeKind;
import com.daisychain.filter.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * Moves fluid from one pulling side to the network's destinations (step 26).
 * Every move is: simulate drain, simulate fill, real drain of what the destination accepted, real fill of exactly
 * what was drained. Anything a destination refuses after promising to take it goes back to the source, then to the
 * other destinations. Fluid is never created here.
 * Same order rules as items: highest priority first; Nearest / Farthest / Round robin within a priority.
 * Filters (step 27): the pull side's filter decides which fluids leave the source; a destination's insert filter
 * decides what it accepts.
 * Amounts (step 29), all in mB, same meaning as for items: Batch (max per transfer, capped by the upgrade), Keep in
 * source (leave this much of each fluid), Fill destination to (stop at this much of the fluid), the destination's
 * Max stock; a matching filter entry (Allow mode) can set its own. Counting a source or destination looks at every one
 * of its tanks once per transfer (cached); handlers with more tanks than max_slots_counted aren't counted (Keep: left
 * alone; Fill / Max stock: that destination gets nothing). Nothing here allocates except FluidStack copies per move.
 */
final class FluidTransfer {
    /** Which resource this class moves (per-resource settings and status bits, step 31). */
    private static final int RESOURCE = PipeKind.FLUIDS;
    /** roomFor result: neither the pull side's Fill nor the destination's Max stock limits this fluid. */
    private static final long NO_LIMIT = Long.MAX_VALUE;
    /** roomFor result: the destination has more tanks than may be counted, so it is skipped. */
    private static final long CANT_COUNT = Long.MIN_VALUE;

    private static boolean warnedAboutLeftover;

    private FluidTransfer() {
    }

    /**
     * Returns the mB moved. extractor.calls holds the handler calls this transfer made.
     * tierAmount = most mB this transfer may move (the upgrade's amount); sideBatch limits fluids that use the side's
     * values (0 = Max). keep / fill = the side's Keep in source / Fill destination to (0 = off). filter is null when the
     * side has no filter entries (every fluid passes). maxTanks caps the source tanks looked at; maxCounted is the most
     * tanks a handler may have and still be counted.
     */
    static int transfer(ServerLevel level, Extractor extractor, Direction side, List<PipeNetwork.Endpoint> destinations,
                        Distribution distribution, boolean allowSelfFeed, @Nullable ItemFilter filter, boolean deny,
                        int tierAmount, int sideBatch, int keep, int fill, int maxCounted, int maxTanks,
                        int maxCalls) {
        extractor.calls = 0;
        extractor.countFluidSize = 0;
        extractor.fluidFillSize = 0;
        extractor.fillSkipped = false;
        extractor.fillChecked = false;
        if (destinations.isEmpty() || tierAmount <= 0) {
            return 0;
        }
        IFluidHandler source = extractor.fluidSource(level, side);
        if (source == null) {
            return 0;
        }
        int allTanks = source.getTanks();
        int tanks = Math.min(allTanks, maxTanks);
        extractor.calls++;
        BlockPos sourcePos = extractor.pos.relative(side);
        int sideIndex = side.get3DDataValue();
        boolean overrides = filter != null && !deny && filter.hasOverrides();
        boolean anyKeep = keep > 0 || (overrides && filter.hasKeepOverride());
        boolean anyFill = fill > 0 || (overrides && filter.hasFillOverride());
        // Remembered so the Pull side screen can say why nothing moves.
        if (anyKeep && allTanks > maxCounted) {
            extractor.keepPausedMask |= Extractor.statusBit(RESOURCE, sideIndex);
        } else {
            extractor.keepPausedMask &= ~Extractor.statusBit(RESOURCE, sideIndex);
        }
        if (overrides) {
            Arrays.fill(extractor.entryMoved, 0);
        }
        int tierLeft = tierAmount;
        int sideLeft = sideBatch > 0 ? Math.min(sideBatch, tierAmount) : tierAmount;
        int moved = 0;
        for (int tank = 0; tank < tanks && tierLeft > 0 && extractor.calls < maxCalls; tank++) {
            FluidStack inTank = source.getFluidInTank(tank);
            extractor.calls++;
            if (inTank.isEmpty() || (filter != null && !filter.testFluid(inTank, deny))) {
                continue;
            }
            extractor.sawItems = true; // the source has something to give
            // Which amounts apply to this fluid: a matching filter entry's own, else the side's.
            int entry = overrides ? filter.entryForFluid(inTank) : -1;
            int entryBatch = ItemFilter.UNSET;
            int fluidKeep = keep;
            int fluidFill = fill;
            if (entry >= 0) {
                entryBatch = filter.number(entry, ItemFilter.BATCH);
                int entryKeep = filter.number(entry, ItemFilter.KEEP);
                int entryFill = filter.number(entry, ItemFilter.FILL);
                if (entryKeep != ItemFilter.UNSET) {
                    fluidKeep = entryKeep;
                }
                if (entryFill != ItemFilter.UNSET) {
                    fluidFill = entryFill;
                }
            }
            int limit;
            if (entryBatch != ItemFilter.UNSET) {
                int cap = entryBatch == 0 ? tierAmount : Math.min(entryBatch, tierAmount);
                limit = Math.min(tierLeft, cap - extractor.entryMoved[entry]);
            } else {
                limit = Math.min(tierLeft, sideLeft);
            }
            if (fluidKeep > 0) {
                long spare = spareAboveKeep(extractor, source, allTanks, inTank.getFluid(), fluidKeep, maxCounted);
                limit = (int) Math.min(limit, Math.max(0L, spare));
            }
            if (limit <= 0) {
                continue; // batch used up for this fluid, or nothing above "keep"
            }
            FluidStack offer = source.drain(Compat.withAmount(inTank, limit), FluidAction.SIMULATE);
            extractor.calls++;
            if (offer.isEmpty() || !Compat.sameFluidSameData(offer, inTank)) {
                continue;
            }
            int sent = distribution == Distribution.ROUND_ROBIN
                    ? splitRoundRobin(level, extractor, sideIndex, source, offer, destinations, sourcePos,
                            allowSelfFeed, fluidFill, maxCounted, maxCalls)
                    : sendInOrder(level, extractor, source, offer,
                            distribution == Distribution.FARTHEST ? extractor.destinationsFarthest : destinations,
                            sourcePos, allowSelfFeed, fluidFill, maxCounted, maxCalls);
            if (sent > 0 && fluidKeep > 0) {
                takenFromSource(extractor, inTank.getFluid(), sent);
            }
            moved += sent;
            tierLeft -= sent;
            if (entryBatch != ItemFilter.UNSET) {
                extractor.entryMoved[entry] += sent;
            } else {
                sideLeft -= sent;
            }
        }
        // Remembered so the Pull side screen can say why a destination gets nothing.
        if (!anyFill) {
            extractor.fillPausedMask &= ~Extractor.statusBit(RESOURCE, sideIndex);
        } else if (extractor.fillChecked) {
            if (extractor.fillSkipped) {
                extractor.fillPausedMask |= Extractor.statusBit(RESOURCE, sideIndex);
            } else {
                extractor.fillPausedMask &= ~Extractor.statusBit(RESOURCE, sideIndex);
            }
        }
        for (int i = 0; i < extractor.fluidFillSize; i++) {
            extractor.fluidFillTargets[i] = null; // don't keep other mods' handlers alive between transfers
        }
        extractor.fluidFillSize = 0;
        return moved;
    }

    // ---------------------------------------------------------------- counting

    /**
     * mB of this fluid (any data) the source may still give without going below "keep". Counts the source once per
     * fluid per transfer (each tank looked at is one call). Too many tanks to count: 0 (left alone, never over-pulled).
     */
    private static long spareAboveKeep(Extractor extractor, IFluidHandler source, int tanks, Fluid fluid, int keep,
                                       int maxCounted) {
        for (int i = 0; i < extractor.countFluidSize; i++) {
            if (extractor.countFluids[i] == fluid) {
                return extractor.countFluidTotals[i] - keep;
            }
        }
        if (tanks > maxCounted) {
            return 0;
        }
        long total = 0;
        for (int i = 0; i < tanks; i++) {
            FluidStack inTank = source.getFluidInTank(i);
            if (!inTank.isEmpty() && inTank.getFluid() == fluid) {
                total += inTank.getAmount();
            }
        }
        extractor.calls += tanks;
        if (extractor.countFluidSize < extractor.countFluids.length) {
            extractor.countFluids[extractor.countFluidSize] = fluid;
            extractor.countFluidTotals[extractor.countFluidSize] = total;
            extractor.countFluidSize++;
        }
        return total - keep;
    }

    /** Keeps the counted source total right after fluid really left it. */
    private static void takenFromSource(Extractor extractor, Fluid fluid, int amount) {
        for (int i = 0; i < extractor.countFluidSize; i++) {
            if (extractor.countFluids[i] == fluid) {
                extractor.countFluidTotals[i] -= amount;
                return;
            }
        }
    }

    /**
     * mB of this fluid the destination may still receive: the lower of the pull side's Fill destination to (fill,
     * 0 = off) and the destination's own Max stock (0 = off). NO_LIMIT if neither is set; 0 or less when full;
     * CANT_COUNT when it has too many tanks to count. Remembers on the destination whether its Max stock had to skip it.
     */
    private static long roomFor(Extractor extractor, PipeNetwork.Endpoint destination, IFluidHandler target,
                                FluidStack offer, int fill, int maxCounted) {
        int stock = destination.maxStockForFluid(offer);
        int limit;
        if (fill > 0 && stock > 0) {
            limit = Math.min(fill, stock);
        } else if (fill > 0) {
            limit = fill;
        } else if (stock > 0) {
            limit = stock;
        } else {
            return NO_LIMIT;
        }
        long room = roomBelow(extractor, target, offer.getFluid(), limit, maxCounted);
        if (stock > 0) {
            destination.setStockSkipped(RESOURCE, room == CANT_COUNT);
        }
        return room;
    }

    /** limit minus what the destination already holds of this fluid (any data), counted once per transfer. */
    private static long roomBelow(Extractor extractor, IFluidHandler target, Fluid fluid, int limit, int maxCounted) {
        extractor.fillChecked = true;
        for (int i = 0; i < extractor.fluidFillSize; i++) {
            if (extractor.fluidFillTargets[i] == target && extractor.fluidFillFluids[i] == fluid) {
                return limit - extractor.fluidFillTotals[i];
            }
        }
        int tanks = target.getTanks();
        if (tanks > maxCounted) {
            extractor.fillSkipped = true;
            return CANT_COUNT;
        }
        long total = 0;
        for (int i = 0; i < tanks; i++) {
            FluidStack inTank = target.getFluidInTank(i);
            if (!inTank.isEmpty() && inTank.getFluid() == fluid) {
                total += inTank.getAmount();
            }
        }
        extractor.calls += tanks + 1;
        if (extractor.fluidFillSize < extractor.fluidFillTargets.length) {
            extractor.fluidFillTargets[extractor.fluidFillSize] = target;
            extractor.fluidFillFluids[extractor.fluidFillSize] = fluid;
            extractor.fluidFillTotals[extractor.fluidFillSize] = total;
            extractor.fluidFillSize++;
        }
        return limit - total;
    }

    /** Keeps the counted destination total right after fluid really went in. */
    private static void addedToTarget(Extractor extractor, IFluidHandler target, Fluid fluid, int amount) {
        for (int i = 0; i < extractor.fluidFillSize; i++) {
            if (extractor.fluidFillTargets[i] == target && extractor.fluidFillFluids[i] == fluid) {
                extractor.fluidFillTotals[i] += amount;
                return;
            }
        }
    }

    // ---------------------------------------------------------------- sending

    /** Nearest / farthest first: fill destinations in order until the offer is used up. Returns mB moved. */
    private static int sendInOrder(ServerLevel level, Extractor extractor, IFluidHandler source, FluidStack offer,
                                   List<PipeNetwork.Endpoint> ordered, BlockPos sourcePos, boolean allowSelfFeed,
                                   int fill, int maxCounted, int maxCalls) {
        int remaining = offer.getAmount();
        int moved = 0;
        for (int i = 0; i < ordered.size() && remaining > 0 && extractor.calls < maxCalls; i++) {
            PipeNetwork.Endpoint destination = ordered.get(i);
            IFluidHandler target = usableTarget(level, destination, sourcePos, allowSelfFeed, offer);
            if (target == null) {
                continue;
            }
            int want = remaining;
            long room = roomFor(extractor, destination, target, offer, fill, maxCounted);
            if (room != NO_LIMIT) {
                if (room <= 0) {
                    continue; // already holds enough (or can't be counted)
                }
                want = (int) Math.min(want, room);
            }
            int result = tryMove(extractor, source, Compat.withAmount(offer, want), target, ordered);
            if (result < 0) {
                break; // the source no longer has it
            }
            if (result > 0 && room != NO_LIMIT) {
                addedToTarget(extractor, target, offer.getFluid(), result);
            }
            moved += result;
            remaining -= result;
        }
        return moved;
    }

    /**
     * Round robin, one priority at a time: the offer is split evenly between the destinations of the highest priority
     * (starting at the remembered position, remainder +1 each in turn); what they can't take goes to the next pass and
     * then to the next priority. Fill / Max stock cap each share. Returns mB moved.
     */
    private static int splitRoundRobin(ServerLevel level, Extractor extractor, int sideIndex, IFluidHandler source,
                                       FluidStack offer, List<PipeNetwork.Endpoint> destinations, BlockPos sourcePos,
                                       boolean allowSelfFeed, int fill, int maxCounted, int maxCalls) {
        int count = destinations.size();
        int remaining = offer.getAmount();
        int moved = 0;
        int groupStart = 0;
        while (groupStart < count && remaining > 0 && extractor.calls < maxCalls) {
            int priority = destinations.get(groupStart).priority();
            int groupEnd = groupStart + 1;
            while (groupEnd < count && destinations.get(groupEnd).priority() == priority) {
                groupEnd++;
            }
            int size = groupEnd - groupStart;
            int eligible = 0;
            for (int i = groupStart; i < groupEnd; i++) {
                PipeNetwork.Endpoint destination = destinations.get(i);
                if ((allowSelfFeed || !destination.targetPos().equals(sourcePos)) && destination.acceptsFluid(offer)) {
                    eligible++;
                }
            }
            int remembered = extractor.roundRobin[RESOURCE * 6 + sideIndex];
            int position = remembered >= groupStart && remembered < groupEnd ? remembered : groupStart;
            while (eligible > 0 && remaining > 0 && extractor.calls < maxCalls) {
                int base = remaining / eligible;
                int extra = remaining % eligible;
                int movedThisPass = 0;
                int visited = 0;
                int nextPosition = position;
                boolean sourceEmpty = false;
                for (int k = 0; k < size && extractor.calls < maxCalls; k++) {
                    int index = groupStart + (position - groupStart + k) % size;
                    PipeNetwork.Endpoint destination = destinations.get(index);
                    if ((!allowSelfFeed && destination.targetPos().equals(sourcePos)) || !destination.acceptsFluid(offer)) {
                        continue;
                    }
                    int share = base + (visited < extra ? 1 : 0);
                    visited++;
                    if (visited == extra) {
                        nextPosition = groupStart + (index - groupStart + 1) % size;
                    }
                    if (share <= 0) {
                        continue;
                    }
                    IFluidHandler target = destination.fluidHandler(level);
                    if (target == null) {
                        continue;
                    }
                    long room = roomFor(extractor, destination, target, offer, fill, maxCounted);
                    if (room != NO_LIMIT) {
                        if (room <= 0) {
                            continue; // already holds enough (or can't be counted); its share goes to the others
                        }
                        share = (int) Math.min(share, room);
                    }
                    int result = tryMove(extractor, source, Compat.withAmount(offer, share), target, destinations);
                    if (result < 0) {
                        sourceEmpty = true;
                        break;
                    }
                    if (result > 0 && room != NO_LIMIT) {
                        addedToTarget(extractor, target, offer.getFluid(), result);
                    }
                    movedThisPass += result;
                }
                position = nextPosition;
                moved += movedThisPass;
                remaining -= movedThisPass;
                if (sourceEmpty || movedThisPass == 0) {
                    break; // nothing more to give, or nobody here takes more
                }
            }
            extractor.roundRobin[RESOURCE * 6 + sideIndex] = position;
            groupStart = groupEnd;
        }
        return moved;
    }

    @Nullable
    private static IFluidHandler usableTarget(ServerLevel level, PipeNetwork.Endpoint destination, BlockPos sourcePos,
                                              boolean allowSelfFeed, FluidStack offer) {
        if ((!allowSelfFeed && destination.targetPos().equals(sourcePos)) || !destination.acceptsFluid(offer)) {
            return null;
        }
        return destination.fluidHandler(level);
    }

    /**
     * One safe move of up to want.getAmount() mB into target. Returns mB that ended up in the target, 0 if it takes
     * none, or -1 if the source no longer has the fluid.
     */
    private static int tryMove(Extractor extractor, IFluidHandler source, FluidStack want, IFluidHandler target,
                               List<PipeNetwork.Endpoint> others) {
        int accepted = target.fill(want, FluidAction.SIMULATE);
        extractor.calls++;
        if (accepted <= 0) {
            return 0;
        }
        FluidStack drained = source.drain(Compat.withAmount(want, Math.min(accepted, want.getAmount())), FluidAction.EXECUTE);
        extractor.calls++;
        if (drained.isEmpty()) {
            return -1;
        }
        int filled = target.fill(drained.copy(), FluidAction.EXECUTE);
        extractor.calls++;
        if (filled >= drained.getAmount()) {
            return drained.getAmount();
        }
        // The destination took less than it promised: put the rest back where it came from (or somewhere else).
        putBack(extractor, source, Compat.withAmount(drained, drained.getAmount() - Math.max(0, filled)), target, others);
        return Math.max(0, filled);
    }

    /**
     * Returns fluid that was drained but not accepted: first into the source, then into any other destination.
     * Only a source and every destination refusing fluid they just offered or promised could leave some over; that is
     * logged (fluid can't be dropped as an item).
     */
    private static void putBack(Extractor extractor, IFluidHandler source, FluidStack rest, IFluidHandler failed,
                                List<PipeNetwork.Endpoint> others) {
        rest.shrink(source.fill(rest.copy(), FluidAction.EXECUTE));
        extractor.calls++;
        if (rest.isEmpty()) {
            return;
        }
        if (extractor.data != null && extractor.data.getLevel() instanceof ServerLevel level) {
            for (int i = 0; i < others.size() && !rest.isEmpty(); i++) {
                IFluidHandler other = others.get(i).fluidHandler(level);
                if (other != null && other != failed) {
                    rest.shrink(other.fill(rest.copy(), FluidAction.EXECUTE));
                    extractor.calls++;
                }
            }
        }
        if (!rest.isEmpty() && !warnedAboutLeftover) {
            warnedAboutLeftover = true;
            Daisychain.LOGGER.warn("Fluid handlers near {} refused {} mB of {} they had offered or accepted in "
                    + "simulation; nothing would take it back.", extractor.pos, rest.getAmount(),
                    rest.getDisplayName().getString());
        }
    }
}
