package com.daisychain.filter;

import com.daisychain.compat.Chemicals;
import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModItems;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * A chemical filter entry (step F102, port of 178), the chemical twin of {@link FluidGhost}: the daisychain:chemical
 * item carrying the chemical's type and Mekanism registry name ({@link ChemicalId}) in its daisychain:ghost_chemical
 * data. Only that is stored (no Mekanism type), so saved filters load, and keep their entries, without Mekanism. The
 * item can't be obtained in survival; it only ever lives in filter slots (and in saved filters).
 */
public final class ChemicalGhost {
    /** Longest chemical name kept (a real one is far shorter), so an edited Settings Copier can't bloat saves. */
    private static final int MAX_ID_LENGTH = 256;

    private ChemicalGhost() {
    }

    public static boolean isGhost(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.CHEMICAL_GHOST.get());
    }

    /** The chemical's type and registry name, or null if the stack isn't a chemical ghost (or its data is unreadable). */
    @Nullable
    public static ChemicalId idOf(ItemStack stack) {
        return isGhost(stack) ? ModDataComponents.GHOST_CHEMICAL.get(stack) : null;
    }

    /**
     * A ghost for this chemical; EMPTY for null, an unknown type or an absurdly long name. Doesn't check that the
     * chemical exists (callers that list do; loading keeps unknown ones so filters survive a game without Mekanism).
     */
    public static ItemStack of(@Nullable ChemicalId id) {
        if (id == null || !id.validType() || id.id().toString().length() > MAX_ID_LENGTH) {
            return ItemStack.EMPTY;
        }
        ItemStack ghost = new ItemStack(ModItems.CHEMICAL_GHOST.get());
        ModDataComponents.GHOST_CHEMICAL.set(ghost, id);
        return ghost;
    }

    /**
     * The chemical to list for a clicked stack: a ghost's own chemical, or what a container (chemical tank, jetpack,
     * scuba tank...) holds in its first non-empty tank of any type. Null if none, or without Mekanism.
     */
    @Nullable
    public static ChemicalId fromClicked(ItemStack stack) {
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
        ChemicalId id = fromClicked(clicked);
        return id != null && Chemicals.exists(id) ? of(id) : ItemStack.EMPTY;
    }

    /** True if both are chemical ghosts of the same chemical (same type and name). */
    public static boolean sameChemical(ItemStack a, ItemStack b) {
        ChemicalId first = idOf(a);
        return first != null && first.equals(idOf(b));
    }
}
