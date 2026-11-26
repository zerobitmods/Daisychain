package com.daisychain.network;

import com.daisychain.Daisychain;
import com.daisychain.block.Distribution;
import com.daisychain.block.PipeKind;
import com.daisychain.compat.ChemicalOps;
import com.daisychain.compat.Chemicals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Step 172: moves Mekanism chemicals (gas, infusion, pigment, slurry) from one pulling side of a gas pipe to the
 * network's destinations. Same rules as fluids (FluidTransfer), all in mB: highest priority first; Nearest / Farthest /
 * Round robin within a priority; Batch, Keep in source, Fill destination to and the destination's Max stock. Every move
 * is: simulate extract, simulate insert, real extract of what the destination accepted, real insert of exactly what
 * came out; what a destination refuses after promising goes back to the source, then to the other destinations.
 * Chemicals are never created here. No filters yet (step 175).
 * <p>
 * Names no Mekanism type: handlers and stacks go through {@link ChemicalOps} (H and S are Mekanism's types at run
 * time). If anything Mekanism-side throws or is missing, gas switches off for the session (Chemicals.switchOff); a
 * throw between taking chemicals out and putting them in first tries to put them back.
 */
final class ChemicalTransfer {
    private static final int RESOURCE = PipeKind.CHEMICALS;
    /** roomFor result: neither the pull side's Fill nor the destination's Max stock limits this chemical. */
    private static final long NO_LIMIT = Long.MAX_VALUE;
    /** roomFor result: the destination has more tanks than may be counted, so it is skipped. */
    private static final long CANT_COUNT = Long.MIN_VALUE;

    private static boolean warnedAboutLeftover;

    private ChemicalTransfer() {
    }

    /**
     * Returns the mB moved. extractor.calls holds the handler calls this transfer made. tierAmount = most mB this
     * transfer may move (the upgrade's amount); sideBatch = the side's Batch (0 = Max); keep / fill = the side's Keep in
     * source / Fill destination to (0 = off). maxTanks caps the source tanks looked at; maxCounted is the most tanks a
     * handler may have and still be counted.
     */
    static int transfer(ServerLevel level, Extractor extractor, Direction side, List<PipeNetwork.Endpoint> destinations,
                        Distribution distribution, boolean allowSelfFeed, int tierAmount, int sideBatch, int keep,
                        int fill, int maxCounted, int maxTanks, int maxCalls) {
        extractor.calls = 0;
        extractor.countChemicalSize = 0;
        extractor.chemicalFillSize = 0;
        extractor.fillSkipped = false;
        extractor.fillChecked = false;
        if (destinations.isEmpty() || tierAmount <= 0) {
            return 0;
        }
        ChemicalOps<?, ?> ops = Chemicals.ops();
        if (ops == null) {
            return 0;
        }
        extractor.chemicalMoved = 0;
        try {
            run(level, extractor, side, destinations, distribution, allowSelfFeed, tierAmount, sideBatch, keep, fill,
                    maxCounted, maxTanks, maxCalls, ops);
        } catch (LinkageError | RuntimeException e) {
            Chemicals.switchOff("moving chemicals at " + extractor.pos.toShortString(), e);
        } finally {
            for (int i = 0; i < extractor.chemicalFillSize; i++) {
                extractor.chemicalFillTargets[i] = null; // don't keep other mods' handlers alive between transfers
            }
            extractor.chemicalFillSize = 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, extractor.chemicalMoved);
    }

    @SuppressWarnings("unchecked")
    private static <H, S> void run(ServerLevel level, Extractor extractor, Direction side,
                                   List<PipeNetwork.Endpoint> destinations, Distribution distribution,
                                   boolean allowSelfFeed, int tierAmount, int sideBatch, int keep, int fill,
                                   int maxCounted, int maxTanks, int maxCalls, ChemicalOps<?, ?> anyOps) {
        ChemicalOps<H, S> ops = (ChemicalOps<H, S>) anyOps;
        H source = (H) extractor.chemicalSource(level, side);
        if (source == null) {
            return;
        }
        int allTanks = ops.tanks(source);
        int tanks = Math.min(allTanks, maxTanks);
        extractor.calls++;
        BlockPos sourcePos = extractor.pos.relative(side);
        int sideIndex = side.get3DDataValue();
        if (keep > 0 && allTanks > maxCounted) {
            extractor.keepPausedMask |= Extractor.statusBit(RESOURCE, sideIndex);
        } else {
            extractor.keepPausedMask &= ~Extractor.statusBit(RESOURCE, sideIndex);
        }
        long tierLeft = tierAmount;
        long sideLeft = sideBatch > 0 ? Math.min(sideBatch, tierAmount) : tierAmount;
        for (int tank = 0; tank < tanks && tierLeft > 0 && sideLeft > 0 && extractor.calls < maxCalls; tank++) {
            S inTank = ops.inTank(source, tank);
            extractor.calls++;
            if (ops.isEmpty(inTank)) {
                continue;
            }
            extractor.sawItems = true; // the source has something to give
            Object chemical = ops.chemical(inTank);
            long limit = Math.min(tierLeft, sideLeft);
            if (keep > 0) {
                long spare = spareAboveKeep(extractor, ops, source, allTanks, chemical, keep, maxCounted);
                limit = Math.min(limit, Math.max(0L, spare));
            }
            if (limit <= 0) {
                continue;
            }
            S offer = ops.extract(source, ops.withAmount(inTank, limit), true);
            extractor.calls++;
            if (ops.isEmpty(offer) || ops.chemical(offer) != chemical) {
                continue;
            }
            long sent = distribution == Distribution.ROUND_ROBIN
                    ? splitRoundRobin(level, extractor, sideIndex, ops, source, offer, destinations, sourcePos,
                            allowSelfFeed, fill, maxCounted, maxCalls)
                    : sendInOrder(level, extractor, ops, source, offer,
                            distribution == Distribution.FARTHEST ? extractor.destinationsFarthest : destinations,
                            sourcePos, allowSelfFeed, fill, maxCounted, maxCalls);
            if (sent > 0 && keep > 0) {
                takenFromSource(extractor, chemical, sent);
            }
            tierLeft -= sent;
            sideLeft -= sent;
        }
        if (fill <= 0) {
            extractor.fillPausedMask &= ~Extractor.statusBit(RESOURCE, sideIndex);
        } else if (extractor.fillChecked) {
            if (extractor.fillSkipped) {
                extractor.fillPausedMask |= Extractor.statusBit(RESOURCE, sideIndex);
            } else {
                extractor.fillPausedMask &= ~Extractor.statusBit(RESOURCE, sideIndex);
            }
        }
    }

    // ---------------------------------------------------------------- counting

    /** mB of this chemical the source may still give without going below "keep" (counted once per transfer). */
    private static <H, S> long spareAboveKeep(Extractor extractor, ChemicalOps<H, S> ops, H source, int tanks,
                                              Object chemical, int keep, int maxCounted) {
        for (int i = 0; i < extractor.countChemicalSize; i++) {
            if (extractor.countChemicals[i] == chemical) {
                return extractor.countChemicalTotals[i] - keep;
            }
        }
        if (tanks > maxCounted) {
            return 0;
        }
        long total = 0;
        for (int i = 0; i < tanks; i++) {
            S inTank = ops.inTank(source, i);
            if (!ops.isEmpty(inTank) && ops.chemical(inTank) == chemical) {
                total += ops.amount(inTank);
            }
        }
        extractor.calls += tanks;
        if (extractor.countChemicalSize < extractor.countChemicals.length) {
            extractor.countChemicals[extractor.countChemicalSize] = chemical;
            extractor.countChemicalTotals[extractor.countChemicalSize] = total;
            extractor.countChemicalSize++;
        }
        return total - keep;
    }

    private static void takenFromSource(Extractor extractor, Object chemical, long amount) {
        for (int i = 0; i < extractor.countChemicalSize; i++) {
            if (extractor.countChemicals[i] == chemical) {
                extractor.countChemicalTotals[i] -= amount;
                return;
            }
        }
    }

    /**
     * mB of this chemical the destination may still receive: the lower of the pull side's Fill destination to and the
     * destination's Max stock (0 = off). NO_LIMIT if neither is set; 0 or less when full; CANT_COUNT when too big.
     */
    private static <H, S> long roomFor(Extractor extractor, ChemicalOps<H, S> ops, PipeNetwork.Endpoint destination,
                                       H target, Object chemical, int fill, int maxCounted) {
        int stock = destination.maxStockForChemicals();
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
        long room = roomBelow(extractor, ops, target, chemical, limit, maxCounted);
        if (stock > 0) {
            destination.setStockSkipped(RESOURCE, room == CANT_COUNT);
        }
        return room;
    }

    private static <H, S> long roomBelow(Extractor extractor, ChemicalOps<H, S> ops, H target, Object chemical,
                                         int limit, int maxCounted) {
        extractor.fillChecked = true;
        for (int i = 0; i < extractor.chemicalFillSize; i++) {
            if (extractor.chemicalFillTargets[i] == target && extractor.chemicalFillChemicals[i] == chemical) {
                return limit - extractor.chemicalFillTotals[i];
            }
        }
        int tanks = ops.tanks(target);
        if (tanks > maxCounted) {
            extractor.fillSkipped = true;
            return CANT_COUNT;
        }
        long total = 0;
        for (int i = 0; i < tanks; i++) {
            S inTank = ops.inTank(target, i);
            if (!ops.isEmpty(inTank) && ops.chemical(inTank) == chemical) {
                total += ops.amount(inTank);
            }
        }
        extractor.calls += tanks + 1;
        if (extractor.chemicalFillSize < extractor.chemicalFillTargets.length) {
            extractor.chemicalFillTargets[extractor.chemicalFillSize] = target;
            extractor.chemicalFillChemicals[extractor.chemicalFillSize] = chemical;
            extractor.chemicalFillTotals[extractor.chemicalFillSize] = total;
            extractor.chemicalFillSize++;
        }
        return limit - total;
    }

    private static void addedToTarget(Extractor extractor, Object target, Object chemical, long amount) {
        for (int i = 0; i < extractor.chemicalFillSize; i++) {
            if (extractor.chemicalFillTargets[i] == target && extractor.chemicalFillChemicals[i] == chemical) {
                extractor.chemicalFillTotals[i] += amount;
                return;
            }
        }
    }

    // ---------------------------------------------------------------- sending

    /** Nearest / farthest first: fill destinations in order until the offer is used up. Returns mB moved. */
    @SuppressWarnings("unchecked")
    private static <H, S> long sendInOrder(ServerLevel level, Extractor extractor, ChemicalOps<H, S> ops, H source,
                                           S offer, List<PipeNetwork.Endpoint> ordered, BlockPos sourcePos,
                                           boolean allowSelfFeed, int fill, int maxCounted, int maxCalls) {
        Object chemical = ops.chemical(offer);
        long remaining = ops.amount(offer);
        long sent = 0;
        for (int i = 0; i < ordered.size() && remaining > 0 && extractor.calls < maxCalls; i++) {
            PipeNetwork.Endpoint destination = ordered.get(i);
            if ((!allowSelfFeed && destination.targetPos().equals(sourcePos)) || !destination.acceptsChemicals()) {
                continue;
            }
            H target = (H) destination.chemicalHandler(level);
            if (target == null) {
                continue;
            }
            long want = remaining;
            long room = roomFor(extractor, ops, destination, target, chemical, fill, maxCounted);
            if (room != NO_LIMIT) {
                if (room <= 0) {
                    continue;
                }
                want = Math.min(want, room);
            }
            long result = tryMove(level, extractor, ops, source, ops.withAmount(offer, want), target, ordered);
            if (result < 0) {
                break; // the source no longer has it
            }
            if (result > 0 && room != NO_LIMIT) {
                addedToTarget(extractor, target, chemical, result);
            }
            sent += result;
            remaining -= result;
        }
        return sent;
    }

    /**
     * Round robin, one priority at a time: the offer is split evenly between the destinations of the highest priority
     * (starting at the remembered position, remainder +1 each in turn); what they can't take goes to the next pass and
     * then to the next priority. Fill / Max stock cap each share. Returns mB moved.
     */
    @SuppressWarnings("unchecked")
    private static <H, S> long splitRoundRobin(ServerLevel level, Extractor extractor, int sideIndex,
                                               ChemicalOps<H, S> ops, H source, S offer,
                                               List<PipeNetwork.Endpoint> destinations, BlockPos sourcePos,
                                               boolean allowSelfFeed, int fill, int maxCounted, int maxCalls) {
        Object chemical = ops.chemical(offer);
        int count = destinations.size();
        long remaining = ops.amount(offer);
        long sent = 0;
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
                if ((allowSelfFeed || !destination.targetPos().equals(sourcePos)) && destination.acceptsChemicals()) {
                    eligible++;
                }
            }
            int remembered = extractor.roundRobin[RESOURCE * 6 + sideIndex];
            int position = remembered >= groupStart && remembered < groupEnd ? remembered : groupStart;
            while (eligible > 0 && remaining > 0 && extractor.calls < maxCalls) {
                long base = remaining / eligible;
                long extra = remaining % eligible;
                long movedThisPass = 0;
                int visited = 0;
                int nextPosition = position;
                boolean sourceEmpty = false;
                for (int k = 0; k < size && extractor.calls < maxCalls; k++) {
                    int index = groupStart + (position - groupStart + k) % size;
                    PipeNetwork.Endpoint destination = destinations.get(index);
                    if ((!allowSelfFeed && destination.targetPos().equals(sourcePos)) || !destination.acceptsChemicals()) {
                        continue;
                    }
                    long share = base + (visited < extra ? 1 : 0);
                    visited++;
                    if (visited == extra) {
                        nextPosition = groupStart + (index - groupStart + 1) % size;
                    }
                    if (share <= 0) {
                        continue;
                    }
                    H target = (H) destination.chemicalHandler(level);
                    if (target == null) {
                        continue;
                    }
                    long room = roomFor(extractor, ops, destination, target, chemical, fill, maxCounted);
                    if (room != NO_LIMIT) {
                        if (room <= 0) {
                            continue;
                        }
                        share = Math.min(share, room);
                    }
                    long result = tryMove(level, extractor, ops, source, ops.withAmount(offer, share), target,
                            destinations);
                    if (result < 0) {
                        sourceEmpty = true;
                        break;
                    }
                    if (result > 0 && room != NO_LIMIT) {
                        addedToTarget(extractor, target, chemical, result);
                    }
                    movedThisPass += result;
                }
                position = nextPosition;
                sent += movedThisPass;
                remaining -= movedThisPass;
                if (sourceEmpty || movedThisPass == 0) {
                    break;
                }
            }
            extractor.roundRobin[RESOURCE * 6 + sideIndex] = position;
            groupStart = groupEnd;
        }
        return sent;
    }

    /**
     * One safe move of up to want's amount into target. Returns mB that ended up in the target, 0 if it takes none, or
     * -1 if the source no longer has the chemical. If the real insert throws, what was taken out is put back first,
     * then the throw goes on (and switches gas off).
     */
    private static <H, S> long tryMove(ServerLevel level, Extractor extractor, ChemicalOps<H, S> ops, H source, S want,
                                       H target, List<PipeNetwork.Endpoint> others) {
        long accepted = ops.insert(target, want, true);
        extractor.calls++;
        if (accepted <= 0) {
            return 0;
        }
        S taken = ops.extract(source, ops.withAmount(want, Math.min(accepted, ops.amount(want))), false);
        extractor.calls++;
        if (ops.isEmpty(taken)) {
            return -1;
        }
        long inserted;
        try {
            inserted = ops.insert(target, taken, false);
        } catch (LinkageError | RuntimeException e) {
            putBack(level, extractor, ops, source, taken, target, others);
            throw e;
        }
        extractor.calls++;
        long took = Math.max(0, Math.min(inserted, ops.amount(taken)));
        extractor.chemicalMoved += took;
        if (took < ops.amount(taken)) {
            // The destination took less than it promised: put the rest back where it came from (or somewhere else).
            putBack(level, extractor, ops, source, ops.withAmount(taken, ops.amount(taken) - took), target, others);
        }
        return took;
    }

    /**
     * Returns chemicals that were taken out but not accepted: first into the source, then into any other destination.
     * Each step is guarded, so a throwing handler can't stop the rest from being tried. Logged once if some is left.
     */
    @SuppressWarnings("unchecked")
    private static <H, S> void putBack(ServerLevel level, Extractor extractor, ChemicalOps<H, S> ops, H source, S rest,
                                       @Nullable H failed, List<PipeNetwork.Endpoint> others) {
        long left = ops.amount(rest);
        try {
            left -= ops.insert(source, rest, false);
        } catch (LinkageError | RuntimeException ignored) {
            // the source refuses by throwing: try the others
        }
        extractor.calls++;
        for (int i = 0; i < others.size() && left > 0; i++) {
            try {
                H other = (H) others.get(i).chemicalHandler(level);
                if (other != null && other != failed) {
                    left -= ops.insert(other, ops.withAmount(rest, left), false);
                    extractor.calls++;
                }
            } catch (LinkageError | RuntimeException ignored) {
                // try the next one
            }
        }
        if (left > 0 && !warnedAboutLeftover) {
            warnedAboutLeftover = true;
            Daisychain.LOGGER.warn("Chemical handlers near {} refused {} mB they had offered or accepted in simulation; "
                    + "nothing would take it back.", extractor.pos, left);
        }
    }
}
