package com.daisychain.network;

import com.daisychain.Daisychain;
import com.daisychain.block.Distribution;
import com.daisychain.block.PipeKind;
import com.daisychain.filter.ItemFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * Moves items from one pulling side to the network's destinations.
 * Every move is: simulate extract, simulate insert, real extract, real insert of exactly what was
 * extracted. Anything a destination refuses after promising to take it goes back to the source.
 * Items are never created or deleted here.
 */
final class ItemTransfer {
    /** Which resource this class moves (per-resource settings and status bits, step 31). */
    private static final int RESOURCE = PipeKind.ITEMS;
    /** tryMove result: the source no longer had the items. */
    private static final int SOURCE_CHANGED = -1;
    /** roomBelowFill result: the destination has more slots than may be counted, so it is skipped. */
    private static final int CANT_COUNT = Integer.MIN_VALUE;
    /** roomFor result: neither the pull side's Fill nor the destination's Max stock limits this item. */
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    private static boolean warnedAboutDrop;

    private ItemTransfer() {
    }

    /**
     * Returns the number of items moved. extractor.calls holds the inventory calls this transfer made.
     * filter is null when the side has no filter entries (everything passes).
     * keep > 0 leaves at least that many of each kind of item in the source; maxCounted is the biggest inventory
     * (in slots) that may be counted for it. Bigger inventories are left alone.
     * fill > 0 stops sending an item to a destination once it holds that many of it (any data). Destinations with more
     * than maxCounted slots can't be counted and get nothing while fill is on (never overfilled).
     * tierItems is the most items one transfer may move (the upgrade's amount). sideBatch (0 = Max) limits the items
     * that use the side's values; a filter entry with its own Batch / Keep / Fill (Allow mode only) uses those instead
     * for the items it matches (ItemFilter.entryFor), each entry's batch counted separately.
     */
    static int transfer(ServerLevel level, Extractor extractor, Direction side, List<PipeNetwork.Endpoint> destinations,
                        Distribution distribution, boolean allowSelfFeed, @Nullable ItemFilter filter, boolean deny,
                        int tierItems, int sideBatch, int keep, int fill, int maxCounted, int maxSlots,
                        int maxCalls) {
        extractor.calls = 0;
        extractor.countSize = 0;
        extractor.fillSize = 0;
        extractor.fillSkipped = false;
        extractor.fillChecked = false;
        int destinationCount = destinations.size();
        if (destinationCount == 0) {
            return 0;
        }
        IItemHandler source = extractor.source(level, side);
        if (source == null) {
            return 0;
        }
        int slots = source.getSlots();
        if (slots <= 0) {
            return 0;
        }
        BlockPos sourcePos = extractor.pos.relative(side);
        int sideIndex = side.get3DDataValue();
        boolean overrides = filter != null && !deny && filter.hasOverrides();
        boolean anyKeep = keep > 0 || (overrides && filter.hasKeepOverride());
        boolean anyFill = fill > 0 || (overrides && filter.hasFillOverride());
        // Remembered so the Pull side screen can say why nothing moves.
        if (anyKeep && slots > maxCounted) {
            extractor.keepPausedMask |= Extractor.statusBit(RESOURCE, sideIndex);
        } else {
            extractor.keepPausedMask &= ~Extractor.statusBit(RESOURCE, sideIndex);
        }
        boolean roundRobin = distribution == Distribution.ROUND_ROBIN;
        boolean farthestFirst = distribution == Distribution.FARTHEST;

        int start = Math.floorMod(extractor.slotCursor[sideIndex], slots);
        int slotLimit = Math.min(slots, maxSlots);
        int tierLeft = tierItems;
        int sideLeft = sideBatch > 0 ? Math.min(sideBatch, tierItems) : tierItems;
        if (overrides) {
            Arrays.fill(extractor.entryMoved, 0);
        }
        int moved = 0;
        int slot = start;
        int scanned = 0;

        while (scanned < slotLimit && tierLeft > 0 && (sideLeft > 0 || overrides) && extractor.calls < maxCalls) {
            slot = (start + scanned) % slots;
            if (filter != null) {
                // Look first (one cheap call), so slots the filter rejects never get a simulated extract.
                ItemStack present = source.getStackInSlot(slot);
                extractor.calls++;
                if (present.isEmpty() || !filter.test(present, deny)) {
                    scanned++;
                    continue;
                }
            }
            ItemStack offer = source.extractItem(slot, overrides ? tierLeft : Math.min(tierLeft, sideLeft), true);
            extractor.calls++;
            if (offer.isEmpty() || (filter != null && !filter.test(offer, deny))) {
                scanned++;
                continue;
            }
            extractor.sawItems = true;
            // Which numbers apply to this item: a matching filter entry's own, else the side's.
            int entry = overrides ? filter.entryFor(offer) : -1;
            int entryBatch = ItemFilter.UNSET;
            int itemKeep = keep;
            int itemFill = fill;
            if (entry >= 0) {
                entryBatch = filter.number(entry, ItemFilter.BATCH);
                int entryKeep = filter.number(entry, ItemFilter.KEEP);
                int entryFill = filter.number(entry, ItemFilter.FILL);
                if (entryKeep != ItemFilter.UNSET) {
                    itemKeep = entryKeep;
                }
                if (entryFill != ItemFilter.UNSET) {
                    itemFill = entryFill;
                }
            }
            int limit;
            if (entryBatch != ItemFilter.UNSET) {
                int cap = entryBatch == 0 ? tierItems : Math.min(entryBatch, tierItems);
                limit = Math.min(tierLeft, cap - extractor.entryMoved[entry]);
            } else {
                limit = Math.min(tierLeft, sideLeft);
            }
            if (limit <= 0) {
                scanned++; // this item's batch is used up for this transfer
                continue;
            }
            if (itemKeep > 0) {
                // Only the items above the "keep" amount may leave.
                int spare = spareAboveKeep(extractor, source, slots, offer.getItem(), itemKeep, maxCounted);
                if (spare <= 0) {
                    scanned++;
                    continue;
                }
                limit = Math.min(limit, spare);
            }
            if (offer.getCount() > limit) {
                offer = offer.copyWithCount(limit); // the simulation allowed more, so less is fine too
            }
            Item movedItem = offer.getItem();
            int movedFromSlot = roundRobin
                    ? splitRoundRobin(level, extractor, sideIndex, source, slot, offer, destinations, sourcePos,
                            allowSelfFeed, limit, itemFill, maxCounted, maxCalls)
                    : sendInOrder(level, extractor, source, slot, offer, destinations, sourcePos,
                            allowSelfFeed, farthestFirst, limit, itemFill, maxCounted, maxCalls);
            if (movedFromSlot < 0) {
                scanned++; // the source changed under us; move on
                continue;
            }
            if (itemKeep > 0 && movedFromSlot > 0) {
                takenFromSource(extractor, movedItem, movedFromSlot);
            }
            moved += movedFromSlot;
            tierLeft -= movedFromSlot;
            if (entryBatch != ItemFilter.UNSET) {
                extractor.entryMoved[entry] += movedFromSlot;
            } else {
                sideLeft -= movedFromSlot;
            }
            if (tierLeft > 0) {
                scanned++;
            }
        }
        // Continue where we stopped next time, so big storages are worked through over time.
        extractor.slotCursor[sideIndex] = slot;
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
        for (int i = 0; i < extractor.fillSize; i++) {
            extractor.fillTargets[i] = null; // don't keep other mods' handlers alive between transfers
        }
        extractor.fillSize = 0;
        return moved;
    }

    /**
     * How many more of this item the destination may receive before it holds "fill" (can be 0 or less: full).
     * Counts the destination once per (destination, item) per transfer; each slot looked at is one inventory call.
     * Returns CANT_COUNT if the destination has more slots than maxCounted.
     */
    private static int roomBelowFill(Extractor extractor, IItemHandler target, Item item, int fill, int maxCounted) {
        extractor.fillChecked = true;
        for (int i = 0; i < extractor.fillSize; i++) {
            if (extractor.fillTargets[i] == target && extractor.fillItems[i] == item) {
                return fill - extractor.fillTotals[i];
            }
        }
        int slots = target.getSlots();
        if (slots > maxCounted) {
            extractor.fillSkipped = true;
            return CANT_COUNT;
        }
        int total = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = target.getStackInSlot(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        extractor.calls += slots;
        if (extractor.fillSize < extractor.fillTargets.length) {
            extractor.fillTargets[extractor.fillSize] = target;
            extractor.fillItems[extractor.fillSize] = item;
            extractor.fillTotals[extractor.fillSize] = total;
            extractor.fillSize++;
        }
        return fill - total;
    }

    /**
     * How many more of this item the destination may receive: the lower of the pull side's Fill destination to
     * (fill, 0 = off) and the destination's own Max stock for the item (0 = off). NO_LIMIT if neither is set;
     * can be 0 or less (full) or CANT_COUNT (the destination is too big to count, so it gets nothing).
     * Remembers on the destination whether its Max stock had to skip it, for its screen's notice.
     */
    private static int roomFor(Extractor extractor, PipeNetwork.Endpoint destination, IItemHandler target,
                               ItemStack offer, int fill, int maxCounted) {
        int stock = destination.maxStockFor(offer);
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
        int room = roomBelowFill(extractor, target, offer.getItem(), limit, maxCounted);
        if (stock > 0) {
            destination.setStockSkipped(RESOURCE, room == CANT_COUNT);
        }
        return room;
    }

    /** Keeps the counted destination total right after items really went in. */
    private static void addedToTarget(Extractor extractor, IItemHandler target, Item item, int amount) {
        for (int i = 0; i < extractor.fillSize; i++) {
            if (extractor.fillTargets[i] == target && extractor.fillItems[i] == item) {
                extractor.fillTotals[i] += amount;
                return;
            }
        }
    }

    /**
     * How many of this item may still leave the source without going below "keep". Counts the whole source once per
     * kind of item per transfer (each slot looked at is one inventory call). If the source has more slots than
     * maxCounted it can't be counted, so nothing is spared: the item is left alone rather than over-pulled.
     */
    private static int spareAboveKeep(Extractor extractor, IItemHandler source, int slots, Item item, int keep,
                                      int maxCounted) {
        for (int i = 0; i < extractor.countSize; i++) {
            if (extractor.countItems[i] == item) {
                return extractor.countTotals[i] - keep;
            }
        }
        if (slots > maxCounted) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = source.getStackInSlot(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        extractor.calls += slots;
        if (extractor.countSize < extractor.countItems.length) {
            extractor.countItems[extractor.countSize] = item;
            extractor.countTotals[extractor.countSize] = total;
            extractor.countSize++;
        }
        return total - keep;
    }

    /** Keeps the counted total right after items really left the source. */
    private static void takenFromSource(Extractor extractor, Item item, int amount) {
        for (int i = 0; i < extractor.countSize; i++) {
            if (extractor.countItems[i] == item) {
                extractor.countTotals[i] -= amount;
                return;
            }
        }
    }

    /**
     * Nearest/farthest first: fill destinations in order (highest priority first, then by distance).
     * Destinations whose insert filter refuses the item are skipped. Returns items moved, or -1 if the source changed.
     */
    private static int sendInOrder(ServerLevel level, Extractor extractor, IItemHandler source, int slot, ItemStack offer,
                                   List<PipeNetwork.Endpoint> destinations, BlockPos sourcePos, boolean allowSelfFeed,
                                   boolean farthestFirst, int remaining, int fill, int maxCounted, int maxCalls) {
        // Both lists are highest priority first; they differ only in the order within one priority.
        List<PipeNetwork.Endpoint> ordered = farthestFirst ? extractor.destinationsFarthest : destinations;
        int count = ordered.size();
        int moved = 0;
        for (int step = 0; step < count && remaining > 0 && extractor.calls < maxCalls; step++) {
            PipeNetwork.Endpoint destination = ordered.get(step);
            IItemHandler target = usableTarget(level, destination, sourcePos, allowSelfFeed, offer);
            if (target == null) {
                continue;
            }
            ItemStack toSend = offer;
            int room = roomFor(extractor, destination, target, offer, fill, maxCounted);
            if (room != NO_LIMIT) {
                if (room <= 0) {
                    continue; // already holds enough (or can't be counted)
                }
                if (room < offer.getCount()) {
                    toSend = offer.copyWithCount(room);
                }
            }
            int result = tryMove(level, extractor, source, slot, toSend, target, extractor.pos);
            if (result == SOURCE_CHANGED) {
                return moved > 0 ? moved : -1;
            }
            if (result == 0) {
                continue;
            }
            if (room != NO_LIMIT) {
                addedToTarget(extractor, target, toSend.getItem(), result);
            }
            moved += result;
            remaining -= result;
            if (remaining <= 0) {
                break;
            }
            offer = source.extractItem(slot, remaining, true);
            extractor.calls++;
            if (offer.isEmpty()) {
                break;
            }
        }
        return moved;
    }

    /**
     * Round robin by splitting, one priority at a time: the highest priority destinations share the items first;
     * only what they can't take goes on to the next priority. Within one priority, the items actually in this slot
     * (up to the batch size) are divided evenly between the destinations that accept the item, starting at the
     * remembered position; any remainder goes one extra each to the first destinations in turn. When every destination
     * accepts everything this gives exactly the same result as sending one item at a time.
     * Returns items moved, or -1 if the source changed.
     */
    private static int splitRoundRobin(ServerLevel level, Extractor extractor, int sideIndex, IItemHandler source,
                                       int slot, ItemStack offer, List<PipeNetwork.Endpoint> destinations,
                                       BlockPos sourcePos, boolean allowSelfFeed, int remaining, int fill,
                                       int maxCounted, int maxCalls) {
        int count = destinations.size();
        int moved = 0;
        int groupStart = 0;
        while (groupStart < count && remaining > 0 && extractor.calls < maxCalls && !offer.isEmpty()) {
            int priority = destinations.get(groupStart).priority();
            int groupEnd = groupStart + 1;
            while (groupEnd < count && destinations.get(groupEnd).priority() == priority) {
                groupEnd++;
            }
            int result = splitGroup(level, extractor, sideIndex, source, slot, offer, destinations, groupStart, groupEnd,
                    sourcePos, allowSelfFeed, remaining, fill, maxCounted, maxCalls);
            if (result < 0) {
                return moved > 0 ? moved : -1;
            }
            moved += result;
            remaining -= result;
            groupStart = groupEnd;
            if (remaining > 0 && groupStart < count) {
                // Whatever this priority couldn't take: see what the slot still has for the next priority.
                offer = source.extractItem(slot, remaining, true);
                extractor.calls++;
            }
        }
        return moved;
    }

    /** Round robin within destinations [from, to) (all the same priority). Returns items moved, or -1. */
    private static int splitGroup(ServerLevel level, Extractor extractor, int sideIndex, IItemHandler source,
                                  int slot, ItemStack offer, List<PipeNetwork.Endpoint> destinations, int from, int to,
                                  BlockPos sourcePos, boolean allowSelfFeed, int remaining, int fill, int maxCounted,
                                  int maxCalls) {
        int size = to - from;
        int eligible = 0;
        for (int i = from; i < to; i++) {
            PipeNetwork.Endpoint destination = destinations.get(i);
            if ((allowSelfFeed || !destination.targetPos().equals(sourcePos)) && destination.accepts(offer)) {
                eligible++;
            }
        }
        if (eligible == 0) {
            return 0;
        }
        int moved = 0;
        int remembered = extractor.roundRobin[RESOURCE * 6 + sideIndex];
        int position = remembered >= from && remembered < to ? remembered : from;
        while (remaining > 0 && extractor.calls < maxCalls && !offer.isEmpty()) {
            int available = Math.min(offer.getCount(), remaining);
            int base = available / eligible;
            int extra = available % eligible;
            int movedThisPass = 0;
            int visited = 0;
            int nextPosition = position;
            for (int k = 0; k < size && extractor.calls < maxCalls; k++) {
                int index = from + (position - from + k) % size;
                PipeNetwork.Endpoint destination = destinations.get(index);
                if ((!allowSelfFeed && destination.targetPos().equals(sourcePos)) || !destination.accepts(offer)) {
                    continue;
                }
                int share = base + (visited < extra ? 1 : 0);
                visited++;
                if (visited == extra) {
                    // next batch starts after the last one that got an extra item
                    nextPosition = from + (index - from + 1) % size;
                }
                if (share <= 0) {
                    continue;
                }
                IItemHandler target = destination.itemHandler(level);
                if (target == null) {
                    continue;
                }
                int room = roomFor(extractor, destination, target, offer, fill, maxCounted);
                if (room != NO_LIMIT) {
                    if (room <= 0) {
                        continue; // already holds enough (or can't be counted); its share goes to the others
                    }
                    share = Math.min(share, room);
                }
                int result = tryMove(level, extractor, source, slot, offer.copyWithCount(share), target,
                        extractor.pos);
                if (result == SOURCE_CHANGED) {
                    extractor.roundRobin[RESOURCE * 6 + sideIndex] = nextPosition;
                    return moved > 0 ? moved : -1;
                }
                if (room != NO_LIMIT && result > 0) {
                    addedToTarget(extractor, target, offer.getItem(), result);
                }
                movedThisPass += result;
            }
            position = nextPosition;
            moved += movedThisPass;
            remaining -= movedThisPass;
            if (movedThisPass == 0 || movedThisPass >= available || remaining <= 0) {
                break; // done, or nobody accepted anything more
            }
            // Someone took less than their share: spread what's left over another pass.
            offer = source.extractItem(slot, remaining, true);
            extractor.calls++;
        }
        extractor.roundRobin[RESOURCE * 6 + sideIndex] = position;
        return moved;
    }

    /**
     * The destination's handler, or null if it has none, would feed the source back into itself, or its insert filter
     * refuses this item.
     */
    @Nullable
    private static IItemHandler usableTarget(ServerLevel level, PipeNetwork.Endpoint destination, BlockPos sourcePos,
                                             boolean allowSelfFeed, ItemStack item) {
        if (!allowSelfFeed && destination.targetPos().equals(sourcePos)) {
            return null;
        }
        if (!destination.accepts(item)) {
            return null;
        }
        return destination.itemHandler(level);
    }

    /**
     * One safe move of up to offer.getCount() items from source slot to target. Returns items inserted, 0 if the target
     * won't take any, or SOURCE_CHANGED. Adds to extractor.calls the same way as before step 36 (the whole simulated
     * insert counts as 1 call, a move as 3), so the per-tick call budget means what it did.
     * Step 36: the simulated insert goes slot by slot and remembers which slots took how many; the real insert then only
     * touches those slots (instead of walking every slot twice). If a slot takes less than it promised, the rest goes
     * through a normal insert into any slot, then back to the source; never lost.
     */
    private static int tryMove(ServerLevel level, Extractor extractor, IItemHandler source, int slot, ItemStack offer,
                               IItemHandler target, BlockPos pipePos) {
        int[] slots = extractor.insertSlots;
        int[] amounts = extractor.insertAmounts;
        int targetSlots = target.getSlots();
        int recorded = 0;
        int accepted = 0;
        ItemStack rest = offer;
        for (int s = 0; s < targetSlots && !rest.isEmpty() && recorded < slots.length; s++) {
            ItemStack after = target.insertItem(s, rest, true);
            int took = rest.getCount() - after.getCount();
            if (took <= 0) {
                continue;
            }
            if (!after.isEmpty() && !ItemStack.isSameItemSameTags(after, rest)) {
                continue; // the slot answered with a different item; don't trust it
            }
            slots[recorded] = s;
            amounts[recorded] = took;
            recorded++;
            accepted += took;
            rest = after;
        }
        extractor.calls++;
        if (accepted <= 0) {
            return 0;
        }
        ItemStack extracted = source.extractItem(slot, accepted, false);
        extractor.calls += 2;
        if (extracted.isEmpty()) {
            return SOURCE_CHANGED;
        }
        int extractedCount = extracted.getCount();
        int left = extractedCount;
        for (int i = 0; i < recorded && left > 0; i++) {
            int want = Math.min(amounts[i], left);
            ItemStack notTaken = target.insertItem(slots[i], extracted.copyWithCount(want), false);
            // Clamped, so a handler that answers nonsense can never make us hand out more than we extracted.
            left -= Math.max(0, Math.min(want, want - notTaken.getCount()));
        }
        if (left <= 0) {
            return extractedCount;
        }
        // A slot took less than it said it would: try the whole target once, then give the rest back to the source.
        ItemStack leftover = ItemHandlerHelper.insertItem(target, extracted.copyWithCount(left), false);
        extractor.calls++;
        if (leftover.isEmpty()) {
            return extractedCount;
        }
        int leftoverCount = leftover.getCount();
        returnToSource(level, source, slot, leftover, pipePos);
        return Math.max(0, extractedCount - leftoverCount);
    }

    /** Puts items back into the source. Only if that also fails are they dropped at the pipe, never deleted. */
    private static void returnToSource(ServerLevel level, IItemHandler source, int slot, ItemStack stack, BlockPos pipePos) {
        ItemStack rest = source.insertItem(slot, stack, false);
        if (!rest.isEmpty()) {
            rest = ItemHandlerHelper.insertItem(source, rest, false);
        }
        if (!rest.isEmpty()) {
            Block.popResource(level, pipePos, rest);
            if (!warnedAboutDrop) {
                warnedAboutDrop = true;
                Daisychain.LOGGER.warn("An inventory near {} refused items it had accepted in simulation; "
                        + "the items were dropped at the pipe instead of being lost.", pipePos);
            }
        }
    }
}
