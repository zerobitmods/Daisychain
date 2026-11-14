package com.daisychain.item;

import com.daisychain.registry.ModItems;
import com.daisychain.registry.ModRecipes;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Step F53 (port of step 125): shapeless, any grid. One block that can be a cover (FacadeItem.canCover) + one item pipe
 * = 8 facades of that block. The block is read from the grid, so this is a special recipe
 * (data/daisychain/recipes/facade.json only names the type).
 */
public final class FacadeRecipe extends CustomRecipe {
    /** Facades per craft: 8 (zerobit, 2026-11-14). */
    public static final int COUNT = 8;

    public FacadeRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer input, Level level) {
        return coverIn(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
        BlockState cover = coverIn(input);
        return cover == null ? ItemStack.EMPTY : FacadeItem.of(cover, COUNT);
    }

    /** The cover block if the grid holds exactly one item pipe and one block that can be a cover; else null. */
    @Nullable
    private static BlockState coverIn(CraftingContainer input) {
        boolean pipe = false;
        BlockState cover = null;
        for (int i = 0; i < input.getContainerSize(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!pipe && stack.is(ModItems.ITEM_PIPE.get())) {
                pipe = true;
            } else if (cover == null) {
                cover = FacadeItem.coverFor(stack);
                if (cover == null) {
                    return null;
                }
            } else {
                return null; // a third item
            }
        }
        return pipe ? cover : null;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.FACADE.get();
    }
}
