package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Daisychain.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.daisychain"))
                    .icon(() -> new ItemStack(ModItems.ITEM_PIPE.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.ITEM_PIPE.get());
                        output.accept(ModItems.FLUID_PIPE.get());
                        for (net.minecraft.world.item.Item energyPipe : ModItems.energyPipes()) {
                            output.accept(energyPipe);
                        }
                        output.accept(ModItems.UNIVERSAL_PIPE.get());
                        output.accept(ModItems.BLOOM_WRENCH.get());
                        output.accept(ModItems.SETTINGS_COPIER.get());
                        output.accept(ModItems.IRON_UPGRADE.get());
                        output.accept(ModItems.GOLD_UPGRADE.get());
                        output.accept(ModItems.DIAMOND_UPGRADE.get());
                        output.accept(ModItems.NETHERITE_UPGRADE.get());
                        output.accept(ModItems.NETHER_STAR_UPGRADE.get());
                        ItemStack guide = guideBook(parameters.holders());
                        if (!guide.isEmpty()) {
                            output.accept(guide);
                        }
                    })
                    .build());

    private ModCreativeTabs() {
    }

    /**
     * Step 75: GuideME's guide item opened to Daisychain's guide (the same item the book + item pipe recipe makes),
     * built from the same JSON as the recipe result. EMPTY if GuideME's item can't be made.
     */
    private static ItemStack guideBook(HolderLookup.Provider registries) {
        JsonObject components = new JsonObject();
        components.addProperty("guideme:guide_id", Daisychain.MOD_ID + ":guide");
        JsonObject json = new JsonObject();
        json.addProperty("id", "guideme:guide");
        json.addProperty("count", 1);
        json.add("components", components);
        return ItemStack.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), json)
                .result().orElse(ItemStack.EMPTY);
    }

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
