package com.daisychain.gametest;

import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.infuse.IInfusionHandler;
import mekanism.api.chemical.infuse.InfusionStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;

import javax.annotation.Nullable;

/**
 * Step F96 (port of 172; game tests only, and only called when Mekanism is loaded): fills and reads Mekanism chemical
 * tanks (gas) through Mekanism's api, and gives barrels a gas handler that takes gas in simulation but throws when it
 * really arrives (to check that a throwing handler switches gas off without losing anything).
 */
final class MekanismTestKit {
    private static final Capability<IGasHandler> GAS = CapabilityManager.get(new CapabilityToken<>() {
    });
    private static final Capability<IInfusionHandler> INFUSION = CapabilityManager.get(new CapabilityToken<>() {
    });
    private static final ResourceLocation THROWING = new ResourceLocation("daisychain", "test_throwing_gas");

    /**
     * The barrel handler only throws while its own test batch runs (GasPipeTests sets this): a barrel left behind in the
     * test world (a failed run) then takes nothing instead of switching gas off for every later test.
     */
    static volatile boolean throwingArmed;

    private MekanismTestKit() {
    }

    private static IGasHandler handler(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        // Through a side (the test tanks have every chemical side on).
        IGasHandler handler = be == null ? null : be.getCapability(GAS, Direction.UP).orElse(null);
        if (handler == null) {
            throw new IllegalStateException("No gas handler at " + pos);
        }
        return handler;
    }

    /** Puts mB of a gas ("mekanism:hydrogen") into the block at pos; returns how much went in. */
    static long fill(ServerLevel level, BlockPos pos, String gasId, long amount) {
        GasStack stack = new GasStack(MekanismAPI.gasRegistry().getValue(new ResourceLocation(gasId)), amount);
        GasStack left = handler(level, pos).insertChemical(stack, Action.EXECUTE);
        return amount - left.getAmount();
    }

    /** mB of gas in all the block's gas tanks. */
    static long amount(ServerLevel level, BlockPos pos) {
        IGasHandler handler = handler(level, pos);
        long total = 0;
        for (int i = 0; i < handler.getTanks(); i++) {
            total += handler.getChemicalInTank(i).getAmount();
        }
        return total;
    }

    private static IInfusionHandler infusionHandler(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        IInfusionHandler handler = be == null ? null : be.getCapability(INFUSION, Direction.UP).orElse(null);
        if (handler == null) {
            throw new IllegalStateException("No infusion handler at " + pos);
        }
        return handler;
    }

    /** Puts mB of an infuse type ("mekanism:redstone") into the block at pos; returns how much went in. */
    static long fillInfusion(ServerLevel level, BlockPos pos, String id, long amount) {
        InfusionStack stack = new InfusionStack(MekanismAPI.infuseTypeRegistry().getValue(new ResourceLocation(id)), amount);
        return amount - infusionHandler(level, pos).insertChemical(stack, Action.EXECUTE).getAmount();
    }

    /** mB of infuse types in all the block's infusion tanks. */
    static long amountInfusion(ServerLevel level, BlockPos pos) {
        IInfusionHandler handler = infusionHandler(level, pos);
        long total = 0;
        for (int i = 0; i < handler.getTanks(); i++) {
            total += handler.getChemicalInTank(i).getAmount();
        }
        return total;
    }

    /** Barrels: a gas handler that accepts anything in simulation and throws on a real insert. */
    static void attachThrowing(AttachCapabilitiesEvent<BlockEntity> event) {
        LazyOptional<IGasHandler> holder = LazyOptional.of(ThrowingHandler::new);
        event.addCapability(THROWING, new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
                return capability == GAS ? holder.cast() : LazyOptional.empty();
            }
        });
        event.addListener(holder::invalidate);
    }

    private static final class ThrowingHandler implements IGasHandler {
        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public GasStack getChemicalInTank(int tank) {
            return GasStack.EMPTY;
        }

        @Override
        public void setChemicalInTank(int tank, GasStack stack) {
        }

        @Override
        public long getTankCapacity(int tank) {
            return 1_000_000;
        }

        @Override
        public boolean isValid(int tank, GasStack stack) {
            return true;
        }

        @Override
        public GasStack insertChemical(int tank, GasStack stack, Action action) {
            if (!throwingArmed) {
                return stack; // takes nothing
            }
            if (action.execute()) {
                throw new IllegalStateException("test handler: throws on a real insert");
            }
            return GasStack.EMPTY;
        }

        @Override
        public GasStack extractChemical(int tank, long amount, Action action) {
            return GasStack.EMPTY;
        }
    }
}
