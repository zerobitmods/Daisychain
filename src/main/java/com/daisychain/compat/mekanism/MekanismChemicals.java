package com.daisychain.compat.mekanism;

import com.daisychain.compat.ChemicalOps;
import com.daisychain.network.CapCache;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.infuse.IInfusionHandler;
import mekanism.api.chemical.pigment.IPigmentHandler;
import mekanism.api.chemical.slurry.ISlurryHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;

/**
 * Step F95 (port of step 171), F96 (port of 172): the only class that names Mekanism. Only {@link com.daisychain.compat.Chemicals} calls
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

    /** Step F96: the four capabilities in Chemicals' type order (gas, infusion, pigment, slurry). */
    private static final Capability<?>[] BY_TYPE = {GAS, INFUSION, PIGMENT, SLURRY};
    /** Step F96: Mekanism's handlers and stacks behind Daisychain's ChemicalOps (one generic adapter for all four). */
    @SuppressWarnings("rawtypes")
    private static final ChemicalOps OPS = new Ops();

    private MekanismChemicals() {
    }

    /** Step F96: CapCaches for the four types of the block at pos, seen from side (made on first use of each). */
    public static Object newCache(ServerLevel level, BlockPos pos, Direction side, @Nullable BooleanSupplier onValid,
                                  @Nullable Runnable onInvalidate) {
        CapCache<?>[] caches = new CapCache<?>[BY_TYPE.length];
        for (int type = 0; type < BY_TYPE.length; type++) {
            caches[type] = onValid == null || onInvalidate == null ? CapCache.create(BY_TYPE[type], level, pos, side)
                    : CapCache.create(BY_TYPE[type], level, pos, side, onValid, onInvalidate);
        }
        return caches;
    }

    /** Step F96: takes the caches' listeners off the block. */
    public static void releaseCache(Object cache) {
        for (CapCache<?> one : (CapCache<?>[]) cache) {
            one.release();
        }
    }

    /** Step F96: the handler of one type the caches hold now, or null. */
    @Nullable
    public static Object handler(Object cache, int type) {
        return ((CapCache<?>[]) cache)[type].getCapability();
    }

    /** Step F96: the adapter for a handler of any type (10.4's handlers share one generic api). */
    @SuppressWarnings("unchecked")
    public static ChemicalOps<?, ?> ops(int type) {
        return OPS;
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class Ops implements ChemicalOps<IChemicalHandler, ChemicalStack> {
        @Override
        public int tanks(IChemicalHandler handler) {
            return handler.getTanks();
        }

        @Override
        public ChemicalStack inTank(IChemicalHandler handler, int tank) {
            return handler.getChemicalInTank(tank);
        }

        @Override
        public boolean isEmpty(ChemicalStack stack) {
            return stack.isEmpty();
        }

        @Override
        public long amount(ChemicalStack stack) {
            return stack.getAmount();
        }

        @Override
        public Object chemical(ChemicalStack stack) {
            return stack.getType();
        }

        @Override
        public ChemicalStack withAmount(ChemicalStack stack, long amount) {
            ChemicalStack copy = stack.copy();
            copy.setAmount(amount);
            return copy;
        }

        @Override
        public ChemicalStack extract(IChemicalHandler handler, ChemicalStack want, boolean simulate) {
            return handler.extractChemical(want, simulate ? Action.SIMULATE : Action.EXECUTE);
        }

        @Override
        public long insert(IChemicalHandler handler, ChemicalStack stack, boolean simulate) {
            ChemicalStack left = handler.insertChemical(stack, simulate ? Action.SIMULATE : Action.EXECUTE);
            return stack.getAmount() - left.getAmount();
        }
    }
}
