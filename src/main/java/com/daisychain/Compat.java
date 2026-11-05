package com.daisychain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * Small helpers for things Minecraft 1.21.1 / NeoForge do in one call and 1.20.1 Forge does differently. Keeps the
 * ported code close to the 1.21.1 version.
 */
public final class Compat {
    private Compat() {
    }

    /** A copy of the fluid stack with this amount (1.21: FluidStack.copyWithAmount). */
    public static FluidStack withAmount(FluidStack stack, int amount) {
        if (stack.isEmpty()) {
            return FluidStack.EMPTY;
        }
        FluidStack copy = stack.copy();
        copy.setAmount(amount);
        return copy;
    }

    /** Same fluid and same data, amount ignored (1.21: FluidStack.isSameFluidSameComponents). */
    public static boolean sameFluidSameData(FluidStack a, FluidStack b) {
        return a.isFluidEqual(b);
    }

    /** The item stack as NBT (1.21: ItemStack.save(registries)). */
    public static CompoundTag save(ItemStack stack) {
        return stack.save(new CompoundTag());
    }

    /** The item stack from NBT, EMPTY if unreadable (1.21: ItemStack.parseOptional). */
    public static ItemStack parse(CompoundTag tag) {
        return tag.isEmpty() ? ItemStack.EMPTY : ItemStack.of(tag);
    }
}
