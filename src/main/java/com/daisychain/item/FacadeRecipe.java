package com.daisychain.item;

import com.daisychain.registry.ModItems;
import com.daisychain.registry.ModRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Step 125: shapeless, any grid. One block that can be a cover (FacadeItem.canCover) + one item pipe = facades of that
 * block. The block is read from the grid, so this is a special recipe (data/daisychain/recipe/facade.json only names
 * the type).
 */
public final class FacadeRecipe extends CustomRecipe {
    /** Facades per craft. Placeholder until the final recipe balance (zerobit, 2026-11-14). */
    public static final int COUNT = 1;

    public FacadeRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return coverIn(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        BlockState cover = coverIn(input);
        return cover == null ? ItemStack.EMPTY : FacadeItem.of(cover, COUNT);
    }

    /** The cover block if the grid holds exactly one item pipe and one block that can be a cover; else null. */
    @Nullable
    private static BlockState coverIn(CraftingInput input) {
        if (input.ingredientCount() != 2) {
            return null;
        }
        boolean pipe = false;
        BlockState cover = null;
        for (int i = 0; i < input.size(); i++) {
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
                return null;
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
