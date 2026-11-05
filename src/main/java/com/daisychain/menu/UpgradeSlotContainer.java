package com.daisychain.menu;

import com.daisychain.block.PipeDataBlockEntity;
import com.daisychain.item.UpgradeItem;
import com.daisychain.registry.ModItems;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Server side of the Pull side screen's upgrade slot. It holds no item of its own: it shows and changes the
 * upgrade level stored in the pipe's block entity, so there is only ever one copy of the upgrade.
 * Two players with the same side open both see the live value, and the server decides every click.
 */
final class UpgradeSlotContainer implements Container {
    private final PipeDataBlockEntity data;
    private final Direction side;
    /** The stack handed to the slot for the current level. Rebuilt whenever the level changes. */
    private ItemStack shown = ItemStack.EMPTY;
    private int shownLevel = -1;

    UpgradeSlotContainer(PipeDataBlockEntity data, Direction side) {
        this.data = data;
        this.side = side;
    }

    private void refresh() {
        int level = data.upgradeLevel(side);
        if (level != shownLevel) {
            UpgradeItem item = ModItems.upgradeFor(level);
            shown = item == null ? ItemStack.EMPTY : new ItemStack(item);
            shownLevel = level;
        }
    }

    /** Forgets the shown stack, so a stack already handed to the player is never treated as installed again. */
    private void forgetShown() {
        shown = ItemStack.EMPTY;
        shownLevel = -1;
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return data.upgradeLevel(side) == 0;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot != 0) {
            return ItemStack.EMPTY;
        }
        refresh();
        return shown;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot != 0 || amount <= 0) {
            return ItemStack.EMPTY;
        }
        UpgradeItem item = ModItems.upgradeFor(data.upgradeLevel(side));
        if (item == null) {
            return ItemStack.EMPTY;
        }
        data.setUpgradeLevel(side, 0);
        forgetShown();
        return new ItemStack(item);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return removeItem(slot, 1);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot != 0) {
            return;
        }
        // The slot only ever passes one item here (max stack size 1), and only upgrade items (canPlaceItem).
        int level = !stack.isEmpty() && stack.getItem() instanceof UpgradeItem upgrade ? upgrade.tier().level() : 0;
        data.setUpgradeLevel(side, level);
        forgetShown();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == 0 && stack.getItem() instanceof UpgradeItem;
    }

    @Override
    public void setChanged() {
        // If code elsewhere emptied the stack we handed out instead of calling removeItem, treat it as removed.
        if (shownLevel > 0 && shown.isEmpty()) {
            data.setUpgradeLevel(side, 0);
            forgetShown();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return !data.isRemoved();
    }

    /** Never deletes the upgrade; it only leaves the pipe by being taken out or dropped when the pipe breaks. */
    @Override
    public void clearContent() {
    }
}
