package com.daisychain.filter;

import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.SimpleFluidContent;

/**
 * A fluid filter entry (step 27). Filters store entries as item stacks, so a fluid is listed as a "fluid ghost": the
 * daisychain:fluid item carrying the fluid in the daisychain:ghost_fluid component (always 1000 mB). The item can't be
 * obtained in survival; it only ever lives in filter slots (and in saved filters).
 */
public final class FluidGhost {
    private FluidGhost() {
    }

    public static boolean isGhost(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.FLUID_GHOST.get());
    }

    /** The fluid a ghost stands for (a copy), or EMPTY. Not for hot paths (copies). */
    public static FluidStack fluidOf(ItemStack stack) {
        if (!isGhost(stack)) {
            return FluidStack.EMPTY;
        }
        SimpleFluidContent content = stack.get(ModDataComponents.GHOST_FLUID.get());
        return content == null ? FluidStack.EMPTY : content.copy();
    }

    /** The fluid of a ghost without copying (null if none). For drawing and comparing. */
    public static SimpleFluidContent contentOf(ItemStack stack) {
        return isGhost(stack) ? stack.get(ModDataComponents.GHOST_FLUID.get()) : null;
    }

    /**
     * The fluid to list for a clicked stack: a ghost's own fluid, or what a container (bucket, tank, cell...) holds.
     * EMPTY if it holds none.
     */
    public static FluidStack fromClicked(ItemStack stack) {
        if (stack.isEmpty()) {
            return FluidStack.EMPTY;
        }
        if (isGhost(stack)) {
            return fluidOf(stack);
        }
        return FluidUtil.getFluidContained(stack).orElse(FluidStack.EMPTY);
    }

    /**
     * A ghost for this fluid (1000 mB). Fluid data bigger than ItemFilter.MAX_DATA_BYTES is dropped, like big item
     * data, so huge fluids can't bloat saves. EMPTY for an empty fluid.
     */
    public static ItemStack of(FluidStack fluid, HolderLookup.Provider registries) {
        if (fluid.isEmpty()) {
            return ItemStack.EMPTY;
        }
        FluidStack kept = fluid.copyWithAmount(1000);
        if (!kept.getComponentsPatch().isEmpty() && kept.save(registries).sizeInBytes() > ItemFilter.MAX_DATA_BYTES) {
            kept = new FluidStack(fluid.getFluidHolder(), 1000);
        }
        ItemStack ghost = new ItemStack(ModItems.FLUID_GHOST.get());
        ghost.set(ModDataComponents.GHOST_FLUID.get(), SimpleFluidContent.copyOf(kept));
        return ghost;
    }

    /** True if the ghost's fluid carries data of its own (needed for "fluid + data" matching). */
    public static boolean hasData(ItemStack ghost) {
        SimpleFluidContent content = contentOf(ghost);
        return content != null && !content.copy().getComponentsPatch().isEmpty();
    }

    public static String modId(Fluid fluid) {
        return BuiltInRegistries.FLUID.getKey(fluid).getNamespace();
    }
}
