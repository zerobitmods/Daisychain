package com.daisychain.item;

import com.daisychain.filter.FluidGhost;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.SimpleFluidContent;

/**
 * Stands for a fluid in a filter slot (step 27). Not in the creative tab and can't be crafted; filter screens draw the
 * fluid's own texture instead of this item's icon. Its name is the fluid's name.
 */
public final class FluidGhostItem extends Item {
    public FluidGhostItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        SimpleFluidContent content = FluidGhost.contentOf(stack);
        if (content == null || content.isEmpty()) {
            return super.getName(stack);
        }
        return content.copy().getHoverName();
    }
}
