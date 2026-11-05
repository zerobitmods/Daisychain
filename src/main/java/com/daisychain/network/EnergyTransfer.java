package com.daisychain.network;

import com.daisychain.Daisychain;
import com.daisychain.block.Distribution;
import com.daisychain.block.PipeKind;
import com.daisychain.config.UpgradeSpeeds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.List;

/**
 * Moves energy (FE) from one pulling side to the network's destinations (step 30). The pipe never stores energy.
 * Every move is: simulate receive, real extract of what the destination accepted, real receive of exactly what was
 * extracted. Anything a destination refuses after promising to take it goes back to the source, then to the other
 * destinations. Energy is never created here.
 * Same order rules as items and fluids: highest priority first; Nearest / Farthest / Round robin within a priority.
 * Amounts, in FE: Batch (max per transfer, capped by the upgrade), Keep in source (leave this much in the source),
 * Fill destination to (stop once a destination holds this much), the destination's Max stock. An energy storage is one
 * number, so counting is a single call and never paused. No allocation.
 */
final class EnergyTransfer {
    /** Which resource this class moves (per-resource settings and status bits, step 31). */
    private static final int RESOURCE = PipeKind.ENERGY_RES;
    /** roomFor result: neither the pull side's Fill nor the destination's Max stock limits it. */
    private static final long NO_LIMIT = Long.MAX_VALUE;

    private static boolean warnedAboutLeftover;

    private EnergyTransfer() {
    }

    /**
     * Returns the FE moved. extractor.calls holds the handler calls this transfer made.
     * tierAmount = most FE this transfer may move (the upgrade's amount); sideBatch = the side's Batch (0 = Max);
     * keep / fill = the side's Keep in source / Fill destination to (0 = off).
     */
    static int transfer(ServerLevel level, Extractor extractor, Direction side, List<PipeNetwork.Endpoint> destinations,
                        Distribution distribution, boolean allowSelfFeed, int tierAmount, int sideBatch, int keep,
                        int fill, int maxCalls) {
        extractor.calls = 0;
        int sideIndex = side.get3DDataValue();
        // An energy storage can always be counted (one call), so these notices never apply to energy pipes.
        extractor.keepPausedMask &= ~Extractor.statusBit(RESOURCE, sideIndex);
        extractor.fillPausedMask &= ~Extractor.statusBit(RESOURCE, sideIndex);
        if (destinations.isEmpty() || tierAmount <= 0) {
            return 0;
        }
        IEnergyStorage source = extractor.energySource(level, side);
        if (source == null) {
            return 0;
        }
        if (extractor.kind == PipeKind.ENERGY) {
            // Step 84: each destination gets at most what the route to it carries in this transfer. Routes slower to
            // refill than this side's interval carry proportionally less (all equal with the default config).
            int sideInterval = UpgradeSpeeds.energyInterval(extractor.bestRouteTier);
            for (int t = 0; t < extractor.tierCap.length; t++) {
                long cap = (long) UpgradeSpeeds.energyFe(t) * sideInterval / Math.max(1, UpgradeSpeeds.energyInterval(t));
                extractor.tierCap[t] = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, cap));
            }
            java.util.Arrays.fill(extractor.routeSent, 0);
        }
        int limit = sideBatch > 0 ? Math.min(sideBatch, tierAmount) : tierAmount;
        if (keep > 0) {
            int stored = source.getEnergyStored();
            extractor.calls++;
            long spare = (long) stored - keep;
            if (spare <= 0) {
                extractor.sawItems = stored > 0; // has energy, all of it kept: back off like a blocked side
                return 0;
            }
            limit = (int) Math.min(limit, spare);
        }
        int offer = source.extractEnergy(limit, true);
        extractor.calls++;
        if (offer <= 0) {
            return 0; // empty, or the block doesn't let energy out
        }
        extractor.sawItems = true; // the source has something to give
        BlockPos sourcePos = extractor.pos.relative(side);
        return distribution == Distribution.ROUND_ROBIN
                ? splitRoundRobin(level, extractor, sideIndex, source, offer, destinations, sourcePos, allowSelfFeed,
                        fill, maxCalls)
                : sendInOrder(level, extractor, source, offer,
                        distribution == Distribution.FARTHEST ? extractor.destinationsFarthest : destinations,
                        sourcePos, allowSelfFeed, fill, maxCalls);
    }

    /**
     * FE the destination may still receive: the lower of the pull side's Fill destination to (fill, 0 = off) and the
     * destination's own Max stock (0 = off), minus what it holds. NO_LIMIT if neither is set; 0 or less when full.
     */
    private static long roomFor(Extractor extractor, PipeNetwork.Endpoint destination, IEnergyStorage target,
                                int fill) {
        int stock = destination.maxStockForEnergy();
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
        extractor.calls++;
        return (long) limit - target.getEnergyStored();
    }

    /** Nearest / farthest first: fill destinations in order until the offer is used up. Returns FE moved. */
    private static int sendInOrder(ServerLevel level, Extractor extractor, IEnergyStorage source, int offer,
                                   List<PipeNetwork.Endpoint> ordered, BlockPos sourcePos, boolean allowSelfFeed,
                                   int fill, int maxCalls) {
        int remaining = offer;
        int moved = 0;
        for (int i = 0; i < ordered.size() && remaining > 0 && extractor.calls < maxCalls; i++) {
            PipeNetwork.Endpoint destination = ordered.get(i);
            if ((!allowSelfFeed && destination.targetPos().equals(sourcePos)) || !destination.acceptsEnergy()) {
                continue;
            }
            IEnergyStorage target = destination.energyHandler(level);
            if (target == null || target == source) {
                continue;
            }
            int want = Math.min(remaining, extractor.routeCap(destination));
            if (want <= 0) {
                continue; // no route to it (step 84)
            }
            long room = roomFor(extractor, destination, target, fill);
            if (room != NO_LIMIT) {
                if (room <= 0) {
                    continue; // already holds enough
                }
                want = (int) Math.min(want, room);
            }
            int result = tryMove(level, extractor, source, want, target, ordered);
            if (result < 0) {
                break; // the source has nothing more to give
            }
            extractor.addRouteSent(destination, result);
            moved += result;
            remaining -= result;
        }
        return moved;
    }

    /**
     * Round robin, one priority at a time: the offer is split evenly between the destinations of the highest priority
     * (starting at the remembered position, remainder +1 each in turn); what they can't take goes to the next pass and
     * then to the next priority. Fill / Max stock cap each share. Returns FE moved.
     */
    private static int splitRoundRobin(ServerLevel level, Extractor extractor, int sideIndex, IEnergyStorage source,
                                       int offer, List<PipeNetwork.Endpoint> destinations, BlockPos sourcePos,
                                       boolean allowSelfFeed, int fill, int maxCalls) {
        int count = destinations.size();
        int remaining = offer;
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
                PipeNetwork.Endpoint candidate = destinations.get(i);
                if ((allowSelfFeed || !candidate.targetPos().equals(sourcePos)) && candidate.acceptsEnergy()) {
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
                    if ((!allowSelfFeed && destination.targetPos().equals(sourcePos)) || !destination.acceptsEnergy()) {
                        continue;
                    }
                    int share = base + (visited < extra ? 1 : 0);
                    visited++;
                    if (visited == extra) {
                        nextPosition = groupStart + (index - groupStart + 1) % size;
                    }
                    share = Math.min(share, extractor.routeCap(destination)); // step 84: route speed
                    if (share <= 0) {
                        continue;
                    }
                    IEnergyStorage target = destination.energyHandler(level);
                    if (target == null || target == source) {
                        continue;
                    }
                    long room = roomFor(extractor, destination, target, fill);
                    if (room != NO_LIMIT) {
                        if (room <= 0) {
                            continue; // already holds enough; its share goes to the others
                        }
                        share = (int) Math.min(share, room);
                    }
                    int result = tryMove(level, extractor, source, share, target, destinations);
                    if (result < 0) {
                        sourceEmpty = true;
                        break;
                    }
                    extractor.addRouteSent(destination, result);
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

    /**
     * One safe move of up to want FE into target. Returns FE that ended up in the target, 0 if it takes none, or -1 if
     * the source has nothing more to give.
     */
    private static int tryMove(ServerLevel level, Extractor extractor, IEnergyStorage source, int want,
                               IEnergyStorage target, List<PipeNetwork.Endpoint> others) {
        int accepted = target.receiveEnergy(want, true);
        extractor.calls++;
        if (accepted <= 0) {
            return 0;
        }
        int extracted = source.extractEnergy(Math.min(accepted, want), false);
        extractor.calls++;
        if (extracted <= 0) {
            return -1;
        }
        int received = target.receiveEnergy(extracted, false);
        extractor.calls++;
        if (received >= extracted) {
            return extracted;
        }
        // The destination took less than it promised: put the rest back where it came from (or somewhere else).
        int kept = Math.max(0, received);
        putBack(level, extractor, source, extracted - kept, target, others);
        return kept;
    }

    /**
     * Returns energy that was extracted but not accepted: first into the source, then into any other destination.
     * Only a source and every destination refusing energy they just offered or promised could leave some over; that
     * is logged once (energy can't be dropped).
     */
    private static void putBack(ServerLevel level, Extractor extractor, IEnergyStorage source, int rest,
                                IEnergyStorage failed, List<PipeNetwork.Endpoint> others) {
        rest -= Math.max(0, source.receiveEnergy(rest, false));
        extractor.calls++;
        for (int i = 0; i < others.size() && rest > 0; i++) {
            if (!others.get(i).acceptsEnergy()) {
                continue; // switched off for energy: never push into it, not even to rescue
            }
            IEnergyStorage other = others.get(i).energyHandler(level);
            if (other != null && other != failed && other != source) {
                rest -= Math.max(0, other.receiveEnergy(rest, false));
                extractor.calls++;
            }
        }
        if (rest > 0 && !warnedAboutLeftover) {
            warnedAboutLeftover = true;
            Daisychain.LOGGER.warn("Energy storages near {} refused {} FE they had offered or accepted in simulation; "
                    + "nothing would take it back.", extractor.pos, rest);
        }
    }
}
