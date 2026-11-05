package com.daisychain.compat.jei;

import com.daisychain.client.PullSideScreen;
import com.daisychain.menu.PullSideMenu;
import com.daisychain.net.SetFilterGhostPayload;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import com.daisychain.net.DaisychainNetwork;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Shift+left-click an item or fluid in JEI's ingredient list (or bookmarks) while a side settings screen with a filter
 * is open: it is listed in the first empty filter slot (step 28a, zerobit). Only on those screens; anywhere else, and
 * for things the filter can't list, JEI handles the click as usual.
 * Our listener runs before JEI's (HIGH vs JEI's NORMAL priority) and cancels the press, plus the matching release, so
 * JEI doesn't also open a recipe or give the item in cheat mode. The server checks the request like any filter click.
 */
final class JeiShiftClick {
    @Nullable
    private static IJeiRuntime runtime;
    private static boolean registered;
    /** Mouse button whose release must be swallowed because we handled its press (-1 = none). */
    private static int swallowRelease = -1;

    private JeiShiftClick() {
    }

    static void setRuntime(@Nullable IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        if (jeiRuntime != null && !registered) {
            registered = true;
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, false, ScreenEvent.MouseButtonPressed.Pre.class,
                    JeiShiftClick::onPress);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, false, ScreenEvent.MouseButtonReleased.Pre.class,
                    JeiShiftClick::onRelease);
        }
    }

    private static void onPress(ScreenEvent.MouseButtonPressed.Pre event) {
        IJeiRuntime jei = runtime;
        if (jei == null || event.getButton() != 0 || !Screen.hasShiftDown()
                || !(event.getScreen() instanceof PullSideScreen screen)) {
            return;
        }
        Optional<ITypedIngredient<?>> hovered = jei.getIngredientListOverlay().getIngredientUnderMouse();
        if (hovered.isEmpty()) {
            hovered = jei.getBookmarkOverlay().getIngredientUnderMouse();
        }
        if (hovered.isEmpty()) {
            return; // not over JEI: a normal click in our screen
        }
        PullSideMenu menu = screen.getMenu();
        ItemStack stack = FilterGhostHandler.toFilterStack(menu, hovered.get());
        if (stack.isEmpty()) {
            return; // this filter can't list it (e.g. a fluid on an item pipe): let JEI do its usual thing
        }
        event.setCanceled(true);
        swallowRelease = event.getButton();
        int slot = firstEmptyFilterSlot(menu);
        if (slot >= 0) {
            DaisychainNetwork.sendToServer(new SetFilterGhostPayload(menu.containerId, (byte) slot, stack));
        }
    }

    private static void onRelease(ScreenEvent.MouseButtonReleased.Pre event) {
        if (swallowRelease >= 0 && event.getButton() == swallowRelease) {
            swallowRelease = -1;
            event.setCanceled(true);
        }
    }

    /** Filter slot number (0-53) of the first empty filter slot, or -1 if the filter is full. */
    private static int firstEmptyFilterSlot(PullSideMenu menu) {
        for (Slot slot : menu.slots) {
            if (PullSideMenu.isFilterSlot(slot.index) && slot.isActive() && !slot.hasItem()) {
                return PullSideMenu.filterSlotOf(slot.index);
            }
        }
        return -1;
    }
}
