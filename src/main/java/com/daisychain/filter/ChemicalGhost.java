package com.daisychain.filter;

import com.daisychain.compat.Chemicals;
import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * A chemical filter entry (step 178), the chemical twin of {@link FluidGhost}: the daisychain:chemical item carrying the
 * chemical's Mekanism registry name in the daisychain:ghost_chemical component. Only the name is stored (no Mekanism
 * type), so saved filters load, and keep their entries, without Mekanism. The item can't be obtained in survival; it
 * only ever lives in filter slots (and in saved filters).
 */
public final class ChemicalGhost {
    private ChemicalGhost() {
    }

    public static boolean isGhost(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.CHEMICAL_GHOST.get());
    }

    /** The chemical's registry name, or null if the stack isn't a chemical ghost. */
    @Nullable
    public static ResourceLocation idOf(ItemStack stack) {
        return isGhost(stack) ? stack.get(ModDataComponents.GHOST_CHEMICAL.get()) : null;
    }

    /** Longest chemical name kept (a real one is far shorter), so an edited Settings Copier can't bloat saves. */
    private static final int MAX_ID_LENGTH = 256;

    /**
     * A ghost for this chemical; EMPTY for null or an absurdly long name. Doesn't check that the chemical exists
     * (callers that list do; loading keeps unknown ones so filters survive a game without Mekanism).
     */
    public static ItemStack of(@Nullable ResourceLocation id) {
        if (id == null || id.toString().length() > MAX_ID_LENGTH) {
            return ItemStack.EMPTY;
        }
        ItemStack ghost = new ItemStack(ModItems.CHEMICAL_GHOST.get());
        ghost.set(ModDataComponents.GHOST_CHEMICAL.get(), id);
        return ghost;
    }

    /**
     * The chemical to list for a clicked stack: a ghost's own chemical, or what a container (chemical tank, jetpack,
     * scuba tank...) holds in its first non-empty tank. Null if none, or without Mekanism.
     */
    @Nullable
    public static ResourceLocation fromClicked(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (isGhost(stack)) {
            return idOf(stack);
        }
        return Chemicals.idInItem(stack);
    }

    /** A ghost ready to list for a clicked stack, or EMPTY if it holds no chemical that exists here. */
    public static ItemStack listable(ItemStack clicked) {
        ResourceLocation id = fromClicked(clicked);
        return id != null && Chemicals.exists(id) ? of(id) : ItemStack.EMPTY;
    }

    /** True if both are chemical ghosts of the same chemical. */
    public static boolean sameChemical(ItemStack a, ItemStack b) {
        ResourceLocation first = idOf(a);
        return first != null && first.equals(idOf(b));
    }
}
