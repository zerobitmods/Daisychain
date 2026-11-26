package com.daisychain.compat.mekanism;

import com.daisychain.compat.ChemicalOps;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import javax.annotation.Nullable;
import java.util.function.BooleanSupplier;

/**
 * Step 171: the only class that names Mekanism. Only {@link com.daisychain.compat.Chemicals} calls it, and only when
 * Mekanism is loaded; every call is wrapped there (or in network.ChemicalTransfer), so a Mekanism change that breaks
 * this class switches gas off instead of crashing.
 * <p>
 * Mekanism's chemical capability is reached without Mekanism's internal classes: NeoForge keeps one capability per
 * name, and asking for the same name, type and context (here "mekanism:chemical_handler", Mekanism's published
 * IChemicalHandler, a side) returns the one Mekanism made. If Mekanism ever made it with another type, NeoForge throws
 * and this class fails to load (caught by Chemicals). Only types from Mekanism's api package are used.
 */
public final class MekanismChemicals {
    static final BlockCapability<IChemicalHandler, Direction> CHEMICAL = BlockCapability.createSided(
            ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"), IChemicalHandler.class);

    /** Step 172: Mekanism's handler and stack behind Daisychain's ChemicalOps. */
    public static final ChemicalOps<IChemicalHandler, ChemicalStack> OPS = new Ops();

    private MekanismChemicals() {
    }

    /** True if the block at pos offers chemicals on that side. Not cached: only for connecting pipes. */
    public static boolean hasHandler(Level level, BlockPos pos, Direction side) {
        return level.getCapability(CHEMICAL, pos, side) != null;
    }

    /** Step 172: a capability cache for the chemical handler of the block at pos, seen from side. */
    public static Object newCache(ServerLevel level, BlockPos pos, Direction side, @Nullable BooleanSupplier onValid,
                                  @Nullable Runnable onInvalidate) {
        if (onValid == null || onInvalidate == null) {
            return BlockCapabilityCache.create(CHEMICAL, level, pos, side);
        }
        return BlockCapabilityCache.create(CHEMICAL, level, pos, side, onValid, onInvalidate);
    }

    /** Step 172: the handler a cache from newCache holds now, or null. */
    @SuppressWarnings("unchecked")
    @Nullable
    public static Object handler(Object cache) {
        return ((BlockCapabilityCache<IChemicalHandler, Direction>) cache).getCapability();
    }

    /**
     * Startup check (after every mod registered its capabilities): true if at least one of Mekanism's own blocks
     * offers the capability under this name. False means Mekanism renamed or dropped it, and gas pipes would sit idle.
     */
    public static boolean mekanismBlocksOfferIt(RegisterCapabilitiesEvent event) {
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if ("mekanism".equals(id.getNamespace()) && event.isBlockRegistered(CHEMICAL, block)) {
                return true;
            }
        }
        return false;
    }

    private static final class Ops implements ChemicalOps<IChemicalHandler, ChemicalStack> {
        @Override
        public int tanks(IChemicalHandler handler) {
            return handler.getChemicalTanks();
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
            return stack.getChemical();
        }

        @Override
        public ChemicalStack withAmount(ChemicalStack stack, long amount) {
            return stack.copyWithAmount(amount);
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
