package com.daisychain.gametest;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayDeque;

/**
 * Development game only (review 2026-11-09): inventories that behave like awkward ones from other mods, so the game
 * tests can check that pipes never lose, drop or mis-filter items next to them.
 */
final class TestInventories {
    private TestInventories() {
    }

    /**
     * One drawer seen through two slots, the way Storage Drawers shows it: slot 0 inserts "wherever it fits", slot 1 is
     * the drawer itself. Both lead to the same space, so adding up what each slot would take promises that space twice.
     */
    static final class DrawerLike implements IItemHandler {
        private final int capacity;
        private ItemStack stored;

        DrawerLike(int capacity, ItemStack initial) {
            this.capacity = capacity;
            this.stored = initial.copy();
        }

        int count() {
            return stored.getCount();
        }

        @Override
        public int getSlots() {
            return 2;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == 1 ? stored : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot > 1 || stack.isEmpty()) {
                return stack;
            }
            if (!stored.isEmpty() && !ItemStack.isSameItemSameComponents(stored, stack)) {
                return stack;
            }
            int take = Math.min(capacity - stored.getCount(), stack.getCount());
            if (take <= 0) {
                return stack;
            }
            if (!simulate) {
                if (stored.isEmpty()) {
                    stored = stack.copyWithCount(take);
                } else {
                    stored.grow(take);
                }
            }
            return take == stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - take);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != 1 || amount <= 0 || stored.isEmpty()) {
                return ItemStack.EMPTY;
            }
            int take = Math.min(amount, stored.getCount());
            ItemStack out = stored.copyWithCount(take);
            if (!simulate) {
                stored.shrink(take);
            }
            return out;
        }

        @Override
        public int getSlotLimit(int slot) {
            return capacity;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }

    /** A machine's output: items can be taken out, nothing can be put in, not even put back. */
    static final class OutputOnly extends ItemStackHandler {
        OutputOnly(ItemStack... contents) {
            super(contents.length);
            for (int i = 0; i < contents.length; i++) {
                setStackInSlot(i, contents[i].copy());
            }
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        int count() {
            int total = 0;
            for (int i = 0; i < getSlots(); i++) {
                total += getStackInSlot(i).getCount();
            }
            return total;
        }
    }

    /**
     * One slot that always shows the next stack of a queue, the way a storage network's handler shows "the next thing"
     * once a slot is emptied. Takes nothing back.
     */
    static final class Shifting implements IItemHandler {
        private final ArrayDeque<ItemStack> queue = new ArrayDeque<>();

        Shifting(ItemStack... stacks) {
            for (ItemStack stack : stacks) {
                queue.add(stack.copy());
            }
        }

        private ItemStack head() {
            while (!queue.isEmpty() && queue.peekFirst().isEmpty()) {
                queue.removeFirst();
            }
            return queue.isEmpty() ? ItemStack.EMPTY : queue.peekFirst();
        }

        int count(Item item) {
            int total = 0;
            for (ItemStack stack : queue) {
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == 0 ? head() : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack head = slot == 0 && amount > 0 ? head() : ItemStack.EMPTY;
            if (head.isEmpty()) {
                return ItemStack.EMPTY;
            }
            int take = Math.min(amount, head.getCount());
            ItemStack out = head.copyWithCount(take);
            if (!simulate) {
                head.shrink(take);
            }
            return out;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return false;
        }
    }
}
