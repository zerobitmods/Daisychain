package com.daisychain.item;

import com.daisychain.compat.Chemicals;
import com.daisychain.filter.ChemicalGhost;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Stands for a Mekanism chemical in a filter slot (step 178). Not in the creative tab and can't be crafted; filter
 * screens draw the chemical's own texture instead of this item's icon. Its name is the chemical's name, or "Unknown
 * chemical (id)" when Mekanism isn't there or no longer has it.
 */
public final class ChemicalGhostItem extends Item {
    public ChemicalGhostItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation id = ChemicalGhost.idOf(stack);
        if (id == null) {
            return super.getName(stack);
        }
        Component name = Chemicals.name(id);
        return name != null ? name : Component.translatable("item.daisychain.chemical.unknown", id.toString());
    }
}
