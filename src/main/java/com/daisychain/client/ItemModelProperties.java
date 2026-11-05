package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Step 43: item model properties. "daisychain:loaded" is 1 while a Settings Copier holds settings, which switches its item
 * model to the animated "loaded" texture (models/item/settings_copier.json overrides). Client only.
 */
// FMLClientSetupEvent is a mod-bus event; FML 1.21.1 sends it to the mod bus automatically.
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ItemModelProperties {
    private ItemModelProperties() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // ItemProperties isn't thread-safe: register on the main thread.
        event.enqueueWork(() -> ItemProperties.register(ModItems.SETTINGS_COPIER.get(),
                new ResourceLocation(Daisychain.MOD_ID, "loaded"),
                (stack, level, entity, seed) -> ModDataComponents.COPIED_SETTINGS.has(stack) ? 1.0F : 0.0F));
    }
}
