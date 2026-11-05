package com.daisychain.registry;

import com.daisychain.Daisychain;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Daisychain.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
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
                        ItemStack guide = guideBook();
                        if (!guide.isEmpty()) {
                            output.accept(guide);
                        }
                    })
                    .build());

    private ModCreativeTabs() {
    }

    /** GuideME's guide item opened to Daisychain's guide (the same item the book + item pipe recipe makes). */
    private static ItemStack guideBook() {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("guideme", "guide"));
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        stack.getOrCreateTag().putString("guideId", Daisychain.MOD_ID + ":guide");
        return stack;
    }

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
