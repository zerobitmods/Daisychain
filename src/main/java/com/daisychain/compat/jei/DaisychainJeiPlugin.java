package com.daisychain.compat.jei;

import com.daisychain.Daisychain;
import com.daisychain.client.PullSideScreen;
import com.daisychain.registry.ModItems;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import com.daisychain.item.FacadeRecipe;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Optional JEI integration (step 28). JEI finds this class through the @JeiPlugin annotation and only when JEI is
 * installed, so Daisychain never loads it (or needs JEI) otherwise. Nothing else in Daisychain refers to this package.
 * - Items and fluids can be dragged from JEI's list onto empty filter slots.
 * - Shift+click an item or fluid in JEI's list (or bookmarks) while a side settings screen is open: it is listed in
 *   the first empty filter slot (JeiShiftClick).
 * - The internal daisychain:fluid item (how fluid filter entries are stored) is hidden from JEI's list.
 * - Step 136: the facade recipes and an info page (FacadeJeiRecipes).
 */
@JeiPlugin
public final class DaisychainJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    /** Step 136: each facade cover is its own JEI entry. */
    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        registration.registerSubtypeInterpreter(ModItems.FACADE.get(), FacadeJeiRecipes.SUBTYPES);
    }

    /** Step 136: the special facade recipe gets a crafting page (example blocks cycling with their facades). */
    @Override
    public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        FacadeJeiRecipes.INSTANCE.reset(); // a fresh block list for this JEI start (tags may have changed)
        registration.getCraftingCategory().addExtension(FacadeRecipe.class, FacadeJeiRecipes.INSTANCE);
    }

    /** Step 136: the transparent facade example recipe and the facade info page. */
    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        FacadeJeiRecipes.INSTANCE.register(registration);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(PullSideScreen.class, new FilterGhostHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        runtime.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK,
                List.of(new ItemStack(ModItems.FLUID_GHOST.get())));
        JeiShiftClick.setRuntime(runtime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiShiftClick.setRuntime(null);
    }
}
