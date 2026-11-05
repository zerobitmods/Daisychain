package com.daisychain.compat.jei;

import com.daisychain.client.PullSideScreen;
import com.daisychain.filter.FluidGhost;
import com.daisychain.menu.PullSideMenu;
import com.daisychain.net.SetFilterGhostPayload;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets players drag items (item pipes) or fluids (fluid pipes; also buckets and tanks, which list their fluid) from
 * JEI onto the empty filter slots of a side settings screen. Dropping sends one SetFilterGhostPayload; the server
 * checks it and lists the entry with the same rules as a click. Runs only while dragging, so it may allocate.
 */
final class FilterGhostHandler implements IGhostIngredientHandler<PullSideScreen> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(PullSideScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
        PullSideMenu menu = screen.getMenu();
        ItemStack sent = toFilterStack(menu, ingredient);
        if (sent.isEmpty()) {
            return List.of();
        }
        List<Target<I>> targets = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!PullSideMenu.isFilterSlot(slot.index) || slot.hasItem() || !slot.isActive()) {
                continue; // only empty slots: dropping never replaces an entry
            }
            int filterSlot = PullSideMenu.filterSlotOf(slot.index);
            Rect2i area = new Rect2i(screen.getGuiLeft() + slot.x, screen.getGuiTop() + slot.y, 16, 16);
            targets.add(new Target<>() {
                @Override
                public Rect2i getArea() {
                    return area;
                }

                @Override
                public void accept(I dropped) {
                    PacketDistributor.sendToServer(new SetFilterGhostPayload(menu.containerId, (byte) filterSlot, sent));
                }
            });
        }
        return targets;
    }

    @Override
    public void onComplete() {
    }

    /**
     * What to send for a JEI ingredient on this screen's filter, or EMPTY if this filter can't list it.
     * Item pipes: the item (never a fluid ghost). Fluid pipes: a fluid ghost for a JEI fluid, or for the fluid a
     * bucket / tank / cell holds. Also used by shift-click (JeiShiftClick).
     */
    static ItemStack toFilterStack(PullSideMenu menu, ITypedIngredient<?> ingredient) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!menu.hasFilter() || minecraft.level == null) {
            return ItemStack.EMPTY;
        }
        if (menu.isFluid()) {
            FluidStack fluid = ingredient.getIngredient(NeoForgeTypes.FLUID_STACK).orElse(FluidStack.EMPTY);
            if (fluid.isEmpty()) {
                fluid = FluidGhost.fromClicked(ingredient.getItemStack().orElse(ItemStack.EMPTY));
            }
            return FluidGhost.of(fluid, minecraft.level.registryAccess());
        }
        ItemStack item = ingredient.getItemStack().orElse(ItemStack.EMPTY);
        return item.isEmpty() || FluidGhost.isGhost(item) ? ItemStack.EMPTY : item.copyWithCount(1);
    }
}
