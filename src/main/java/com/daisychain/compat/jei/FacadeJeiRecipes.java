package com.daisychain.compat.jei;

import com.daisychain.Daisychain;
import com.daisychain.config.DaisychainConfig;
import com.daisychain.item.FacadeItem;
import com.daisychain.item.FacadeRecipe;
import com.daisychain.registry.ModItems;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Step 136 (facades step 5): JEI shows the facade recipe, which is a special recipe (the block is read from the grid),
 * so JEI can't work it out on its own.
 * - The real recipe (daisychain:facade) gets a crafting page through this extension: the block slot cycles through
 *   every block item that can be a cover, and the output (8 facades) cycles with it (a focus link), so looking up the
 *   uses of oak planks or the recipe of an Oak Planks Facade lands on the right pair.
 * - A second page, only in JEI, for glass pane + item pipe = 8 transparent facades; left out when the server config
 *   turns transparent facades off (the config is synced at login, before JEI loads its recipes).
 * - An info page on the facades.
 * - Facades are told apart by their cover (subtype), so each cover is its own entry.
 * Only loaded through DaisychainJeiPlugin, so only when JEI is installed.
 */
final class FacadeJeiRecipes implements ICraftingCategoryExtension<FacadeRecipe> {
    static final FacadeJeiRecipes INSTANCE = new FacadeJeiRecipes();

    private static final ResourceLocation TRANSPARENT_EXAMPLE =
            ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "jei/transparent_facade");

    /** Facades are different JEI entries per cover; a facade without one is its own entry too. */
    static final ISubtypeInterpreter<ItemStack> SUBTYPES = new ISubtypeInterpreter<>() {
        @Override
        @Nullable
        public Object getSubtypeData(ItemStack stack, UidContext context) {
            return FacadeItem.coverOf(stack);
        }

        @Override
        public String getLegacyStringSubtypeInfo(ItemStack stack, UidContext context) {
            BlockState cover = FacadeItem.coverOf(stack);
            return cover == null ? "" : BuiltInRegistries.BLOCK.getKey(cover.getBlock()).toString();
        }
    };

    /** Blocks that can be a cover and the 8 facades each makes, index-aligned. */
    private record Examples(List<ItemStack> blocks, List<ItemStack> facades) {
    }

    /**
     * Built the first time JEI asks for it, not in registerRecipes: JEI's own vanilla plugin adds the real
     * daisychain:facade recipe before our registerRecipes runs, and JEI files a recipe under its ingredients right
     * when it is added (RecipeManagerInternal.addRecipe calls setRecipe). Cleared at every JEI start (reset()), so
     * tag and config changes on reload are picked up.
     */
    @Nullable
    private volatile Examples examples;

    private FacadeJeiRecipes() {
    }

    /** Called from DaisychainJeiPlugin.registerVanillaCategoryExtensions, before any plugin adds recipes. */
    void reset() {
        examples = null;
    }

    private Examples examples() {
        Examples built = examples;
        if (built != null) {
            return built;
        }
        List<ItemStack> blockList = new ArrayList<>();
        List<ItemStack> facadeList = new ArrayList<>();
        Set<BlockState> seen = new HashSet<>();
        // Every block item the crafting recipe accepts, in registry order (vanilla stone first). Same check as
        // crafting (FacadeItem.coverFor), so JEI never shows a pair the grid would refuse.
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem)) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            if (stack.is(Tags.Items.GLASS_PANES_COLORLESS)) {
                continue; // panes make transparent facades (their own page)
            }
            BlockState cover = FacadeItem.coverFor(stack);
            if (cover != null && seen.add(cover)) {
                blockList.add(stack);
                facadeList.add(FacadeItem.of(cover, FacadeRecipe.COUNT));
            }
        }
        built = new Examples(List.copyOf(blockList), List.copyOf(facadeList));
        examples = built;
        Daisychain.LOGGER.debug("JEI: facade recipe shows {} cover blocks", built.blocks().size());
        return built;
    }

    /** Called from DaisychainJeiPlugin.registerRecipes (after tags and the server config are known). */
    void register(IRecipeRegistration registration) {
        List<ItemStack> infoOn = new ArrayList<>();
        infoOn.add(new ItemStack(ModItems.FACADE.get()));
        for (ItemStack facade : examples().facades()) {
            infoOn.add(facade.copyWithCount(1));
        }
        if (DaisychainConfig.transparentFacadesAllowed()) {
            List<RecipeHolder<CraftingRecipe>> example = List.of(
                    new RecipeHolder<>(TRANSPARENT_EXAMPLE, new FacadeRecipe(CraftingBookCategory.MISC)));
            registration.addRecipes(RecipeTypes.CRAFTING, example);
            infoOn.add(FacadeItem.of(FacadeItem.transparentCover(), 1));
        }
        registration.addItemStackInfo(infoOn, Component.translatable("jei.daisychain.facade.info"));
    }

    @Override
    public void setRecipe(RecipeHolder<FacadeRecipe> recipe, IRecipeLayoutBuilder builder,
                          ICraftingGridHelper craftingGridHelper, IFocusGroup focuses) {
        List<ItemStack> inputs;
        List<ItemStack> outputs;
        if (TRANSPARENT_EXAMPLE.equals(recipe.id())) {
            inputs = List.of(Ingredient.of(Tags.Items.GLASS_PANES_COLORLESS).getItems());
            outputs = List.of(FacadeItem.of(FacadeItem.transparentCover(), FacadeRecipe.COUNT));
        } else {
            Examples all = examples();
            inputs = all.blocks();
            outputs = all.facades();
        }
        List<IRecipeSlotBuilder> slots = craftingGridHelper.createAndSetInputs(builder, VanillaTypes.ITEM_STACK,
                List.of(inputs, List.of(new ItemStack(ModItems.ITEM_PIPE.get()))), 0, 0);
        IRecipeSlotBuilder output = craftingGridHelper.createAndSetOutputs(builder, VanillaTypes.ITEM_STACK, outputs);
        builder.setShapeless();
        if (inputs.size() == outputs.size() && inputs.size() > 1) {
            builder.createFocusLink(slots.get(0), output); // block and facade cycle together
        }
    }
}
