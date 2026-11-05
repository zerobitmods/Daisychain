package com.daisychain.client;

import com.daisychain.Daisychain;
import com.daisychain.registry.ModDataComponents;
import com.daisychain.registry.ModItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Step 43: item model properties. "daisychain:loaded" is 1 while a Settings Copier holds settings, which switches its item
 * model to the animated "loaded" texture (models/item/settings_copier.json overrides). Client only.
 */
// FMLClientSetupEvent is a mod-bus event; FML 1.21.1 sends it to the mod bus automatically.
@EventBusSubscriber(modid = Daisychain.MOD_ID, value = Dist.CLIENT)
public final class ItemModelProperties {
    private ItemModelProperties() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // ItemProperties isn't thread-safe: register on the main thread.
        event.enqueueWork(() -> ItemProperties.register(ModItems.SETTINGS_COPIER.get(),
                ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "loaded"),
                (stack, level, entity, seed) -> stack.has(ModDataComponents.COPIED_SETTINGS.get()) ? 1.0F : 0.0F));
    }
}
