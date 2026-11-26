package com.daisychain.compat.mekanism;

import com.daisychain.network.CapCache;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.infuse.IInfusionHandler;
import mekanism.api.chemical.pigment.IPigmentHandler;
import mekanism.api.chemical.slurry.ISlurryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

/**
 * Step F95 (port of step 171): the only class that names Mekanism. Only {@link com.daisychain.compat.Chemicals} calls
 * it, and only when Mekanism is loaded; every call is wrapped there, so a Mekanism change that breaks this class
 * switches gas off instead of crashing.
 * <p>
 * Mekanism 10.4 (1.20.1) has four chemical types, each with its own handler and capability: gas, infusion, pigment,
 * slurry. They are reached the standard Forge way, by the handler interface from Mekanism's published api package
 * (CapabilityManager.get with a CapabilityToken), exactly as Mekanism gets them itself; nothing from Mekanism's
 * internal classes.
 */
public final class MekanismChemicals {
    static final Capability<IGasHandler> GAS = CapabilityManager.get(new CapabilityToken<>() {
    });
    static final Capability<IInfusionHandler> INFUSION = CapabilityManager.get(new CapabilityToken<>() {
    });
    static final Capability<IPigmentHandler> PIGMENT = CapabilityManager.get(new CapabilityToken<>() {
    });
    static final Capability<ISlurryHandler> SLURRY = CapabilityManager.get(new CapabilityToken<>() {
    });

    private MekanismChemicals() {
    }

    /** True if the block at pos offers any chemical type on that side. Only for connecting pipes. */
    public static boolean hasHandler(Level level, BlockPos pos, Direction side) {
        return CapCache.has(level, pos, side, GAS) || CapCache.has(level, pos, side, INFUSION)
                || CapCache.has(level, pos, side, PIGMENT) || CapCache.has(level, pos, side, SLURRY);
    }

    /**
     * Startup check (after mods registered their capabilities): true if all four capabilities are registered.
     * False means Mekanism stopped registering one under this interface, and gas pipes would sit idle.
     */
    public static boolean capabilitiesRegistered() {
        return GAS.isRegistered() && INFUSION.isRegistered() && PIGMENT.isRegistered() && SLURRY.isRegistered();
    }
}
