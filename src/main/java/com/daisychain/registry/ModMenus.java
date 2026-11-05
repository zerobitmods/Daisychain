package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.daisychain.menu.PullSideMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Daisychain.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<PullSideMenu>> PULL_SIDE =
            MENUS.register("pull_side", () -> IMenuTypeExtension.create(PullSideMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
