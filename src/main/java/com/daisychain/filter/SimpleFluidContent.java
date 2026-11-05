package com.daisychain.filter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

/**
 * An unchanging copy of a fluid stack (fluid, amount, data), safe to keep in item data. 1.20.1 version of NeoForge's
 * SimpleFluidContent, which Daisychain uses for fluid filter entries.
 */
public final class SimpleFluidContent {
    public static final SimpleFluidContent EMPTY = new SimpleFluidContent(Fluids.EMPTY, 0, null);

    public static final Codec<SimpleFluidContent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BuiltInRegistries.FLUID.byNameCodec().fieldOf("id").forGetter(SimpleFluidContent::getFluid),
            Codec.INT.optionalFieldOf("amount", 1000).forGetter(c -> c.amount),
            CompoundTag.CODEC.optionalFieldOf("tag").forGetter(c -> Optional.ofNullable(c.tag))
    ).apply(instance, (fluid, amount, tag) -> new SimpleFluidContent(fluid, amount, tag.orElse(null))));

    private final Fluid fluid;
    private final int amount;
    @Nullable
    private final CompoundTag tag;

    private SimpleFluidContent(Fluid fluid, int amount, @Nullable CompoundTag tag) {
        this.fluid = fluid;
        this.amount = amount;
        this.tag = tag == null || tag.isEmpty() ? null : tag.copy();
    }

    public static SimpleFluidContent copyOf(FluidStack stack) {
        return stack.isEmpty() ? EMPTY : new SimpleFluidContent(stack.getFluid(), stack.getAmount(), stack.getTag());
    }

    /** A new FluidStack with this content. */
    public FluidStack copy() {
        if (isEmpty()) {
            return FluidStack.EMPTY;
        }
        FluidStack stack = new FluidStack(fluid, amount);
        if (tag != null) {
            stack.setTag(tag.copy());
        }
        return stack;
    }

    public boolean isEmpty() {
        return fluid == Fluids.EMPTY || amount <= 0;
    }

    public Fluid getFluid() {
        return fluid;
    }

    public boolean hasData() {
        return tag != null;
    }

    /** Same fluid and same data as the stack (amount ignored). */
    public boolean isSameFluidSameComponents(FluidStack stack) {
        return stack.getFluid() == fluid && Objects.equals(tag, stack.getTag());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SimpleFluidContent other && other.fluid == fluid && other.amount == amount
                && Objects.equals(other.tag, tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fluid, amount, tag);
    }
}
