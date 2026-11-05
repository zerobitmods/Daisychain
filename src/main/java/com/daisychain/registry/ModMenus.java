package com.daisychain.registry;

import com.daisychain.Daisychain;
import com.daisychain.menu.PullSideMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Daisychain.MOD_ID);

    public static final RegistryObject<MenuType<PullSideMenu>> PULL_SIDE =
            MENUS.register("pull_side", () -> IForgeMenuType.create(PullSideMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
