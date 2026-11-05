package com.daisychain.menu;

import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.filter.ItemFilter;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.IntSupplier;

/**
 * Server side of the 54 filter slots: a live view of the pipe's filter for one side (its pull filter, or its insert
 * filter when the menu is for an insert side).
 * Entries are ghost copies, so nothing here can ever create or delete a real item:
 * taking from these slots is blocked by PullSideMenu and by the slots themselves.
 */
final class FilterSlotContainer implements Container {
    private final PipeDataBlockEntity data;
    private final Direction side;
    private final boolean insert;
    /** The resource the menu shows right now (its filter is the one these slots view). */
    private final IntSupplier resource;

    FilterSlotContainer(PipeDataBlockEntity data, Direction side, boolean insert, IntSupplier resource) {
        this.data = data;
        this.side = side;
        this.insert = insert;
        this.resource = resource;
    }

    @Override
    public int getContainerSize() {
        return ItemFilter.SIZE;
    }

    @Override
    public boolean isEmpty() {
        return !data.hasFilterEntries(side, insert, resource.getAsInt());
    }

    @Override
    public ItemStack getItem(int slot) {
        return data.filterEntry(side, slot, insert, resource.getAsInt());
    }

    /** Never hands out an item: filter entries are not real. */
    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        data.setFilterEntry(side, slot, stack, insert, resource.getAsInt());
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(Player player) {
        return !data.isRemoved();
    }

    @Override
    public void clearContent() {
        data.clearFilter(side, insert, resource.getAsInt());
    }
}
