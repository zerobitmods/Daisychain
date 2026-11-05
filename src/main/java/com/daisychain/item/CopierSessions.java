package com.daisychain.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settings Copier state per player (step 48d), server thread only, never saved:
 * <ul>
 *   <li>the paste waiting for its confirming second click (a side, or a whole network when the center was clicked);</li>
 *   <li>the paste streak: after a confirmed side paste, more sides paste with one click until the player stops for
 *       {@link #STREAK_TICKS};</li>
 *   <li>the last {@link #HISTORY} paste batches (each one network paste, or one streak) with each side's settings from
 *       before, so each can be undone once (step 48e: was only the latest);</li>
 *   <li>the undone batches, newest first, so each can be redone (step 48h); a new paste clears them.</li>
 * </ul>
 * Cleared when the player logs out and when the server stops.
 */
public final class CopierSessions {

    /** How long a "click again to paste" request stays open: 3 seconds. */
    public static final long CONFIRM_TICKS = 60;
    /** How long a paste streak waits for the next side: 5 seconds. */
    public static final long STREAK_TICKS = 100;
    /** How many recent pastes can still be undone. */
    public static final int HISTORY = 5;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private CopierSessions() {
    }

    public static Session of(UUID player) {
        return SESSIONS.computeIfAbsent(player, id -> new Session());
    }

    @Nullable
    public static Session existing(UUID player) {
        return SESSIONS.get(player);
    }

    public static void remove(UUID player) {
        SESSIONS.remove(player);
    }

    public static void clearAll() {
        SESSIONS.clear();
    }

    /** One side as it was before a paste (settings and upgrade) and right after it (to notice later changes). */
    public record SideChange(BlockPos pos, Direction side, boolean insert, CompoundTag before, int beforeUpgrade,
                             CompoundTag after) {
    }

    /** The sides changed by one network paste or one streak of side pastes. */
    public static final class Batch {
        public final int id;
        public final ResourceKey<Level> dimension;
        public final String pipe;
        public final List<SideChange> changes = new ArrayList<>();

        public Batch(int id, ResourceKey<Level> dimension, String pipe) {
            this.id = id;
            this.dimension = dimension;
            this.pipe = pipe;
        }
    }

    public static final class Session {
        private ResourceKey<Level> pendingDimension;
        private BlockPos pendingPos;
        @Nullable
        private Direction pendingSide; // null = the whole network
        private long pendingTime = Long.MIN_VALUE;

        private ResourceKey<Level> streakDimension;
        private long streakUntil = Long.MIN_VALUE;

        private int nextBatchId = 1;
        /** Newest first. */
        private final ArrayDeque<Batch> history = new ArrayDeque<>();
        /** Undone batches that can be redone, newest first. Only the sides the undo actually put back. */
        private final ArrayDeque<Batch> redo = new ArrayDeque<>();
        /** The batch a streak is adding to; null when the next change starts a new paste. */
        @Nullable
        private Batch open;

        /**
         * True if this is the second click on the same target within {@link #CONFIRM_TICKS}; that click uses up the
         * request. Otherwise this click becomes the first one (replacing any older request). side = null: network.
         */
        public boolean confirm(ResourceKey<Level> dimension, BlockPos pos, @Nullable Direction side, long now) {
            boolean match = pendingPos != null && pendingDimension == dimension && pendingPos.equals(pos)
                    && pendingSide == side && now >= pendingTime && now - pendingTime <= CONFIRM_TICKS;
            if (match) {
                pendingPos = null;
                return true;
            }
            pendingDimension = dimension;
            pendingPos = pos.immutable();
            pendingSide = side;
            pendingTime = now;
            return false;
        }

        public boolean inStreak(ResourceKey<Level> dimension, long now) {
            return streakDimension == dimension && now >= streakUntil - STREAK_TICKS && now <= streakUntil;
        }

        public void extendStreak(ResourceKey<Level> dimension, long now) {
            streakDimension = dimension;
            streakUntil = now + STREAK_TICKS;
        }

        /** New copied settings, a clear, or a network paste: requests and streaks start over. */
        public void reset() {
            pendingPos = null;
            streakUntil = Long.MIN_VALUE;
            open = null;
        }

        /** A new paste action begins: its first change starts a new batch (older batches stay undoable). */
        public void newAction() {
            open = null;
        }

        /**
         * Adds a changed side to the open batch, or starts a new one. Returns true if that started a new batch (the
         * caller then shows the Undo button). Keeps at most {@link #HISTORY} batches and about maxSides remembered sides
         * in total (oldest batches go first; the newest is always kept), so memory stays bounded.
         */
        public boolean record(SideChange change, ResourceKey<Level> dimension, String pipe, int maxSides) {
            boolean fresh = open == null || open.dimension != dimension || !open.pipe.equals(pipe)
                    || open.changes.size() >= maxSides;
            if (fresh) {
                open = new Batch(nextBatchId++, dimension, pipe);
                history.addFirst(open);
            }
            open.changes.add(change);
            redo.clear(); // a new paste: what was undone before can't be redone any more
            trim(maxSides);
            return fresh;
        }

        /** Puts a redone batch back in the undo history as the newest paste. */
        public void pushHistory(Batch batch, int maxSides) {
            history.addFirst(batch);
            open = null;
            trim(maxSides);
        }

        /** Remembers an undone batch for redo (newest first, at most HISTORY). */
        public void pushRedo(Batch batch) {
            redo.addFirst(batch);
            while (redo.size() > HISTORY) {
                redo.removeLast();
            }
        }

        /** The newest undone batch, or null. */
        @Nullable
        public Batch peekRedo() {
            return redo.peekFirst();
        }

        public void removeRedo(Batch batch) {
            redo.remove(batch);
        }

        private void trim(int maxSides) {
            int total = 0;
            int kept = 0;
            for (Iterator<Batch> it = history.iterator(); it.hasNext(); ) {
                Batch batch = it.next();
                total += batch.changes.size();
                kept++;
                if (kept > 1 && (kept > HISTORY || total > maxSides)) {
                    it.remove();
                }
            }
        }

        /** The batch the current paste is adding to, or null. */
        @Nullable
        public Batch openBatch() {
            return open;
        }

        /** The batch with this id if it can still be undone; id 0 = the newest. */
        @Nullable
        public Batch find(int id) {
            if (id == 0) {
                return history.peekFirst();
            }
            for (Batch batch : history) {
                if (batch.id == id) {
                    return batch;
                }
            }
            return null;
        }

        /** Removes a batch for undoing. Undoing the open batch also ends its streak. */
        public void remove(Batch batch) {
            history.remove(batch);
            if (batch == open) {
                open = null;
                streakUntil = Long.MIN_VALUE;
            }
        }
    }
}
