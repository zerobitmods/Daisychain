package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.compat.Chemicals;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Step F96 (port of 172; game tests only): with Mekanism loaded, barrels get a gas handler that throws on a real
 * insert (MekanismTestKit). This class names no Mekanism type, so it loads fine without Mekanism.
 */
@Mod.EventBusSubscriber(modid = Daisychain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GasTestCapabilities {
    private GasTestCapabilities() {
    }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        if (!FMLEnvironment.production && Chemicals.mekanismLoaded() && event.getObject() instanceof BarrelBlockEntity) {
            MekanismTestKit.attachThrowing(event);
        }
    }
}
