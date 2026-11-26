package com.daisychain.compat.mekanism;

import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;


/**
 * Step 171: the only class that names Mekanism. Only {@link com.daisychain.compat.Chemicals} calls it, and only when
 * Mekanism is loaded; every call is wrapped there, so a Mekanism change that breaks this class switches gas off instead
 * of crashing.
 * <p>
 * Mekanism's chemical capability is reached without Mekanism's internal classes: NeoForge keeps one capability per
 * name, and asking for the same name, type and context (here "mekanism:chemical_handler", Mekanism's published
 * IChemicalHandler, a side) returns the one Mekanism made. If Mekanism ever made it with another type, NeoForge throws
 * and this class fails to load (caught by Chemicals). Only types from Mekanism's api package are used.
 */
public final class MekanismChemicals {
    static final BlockCapability<IChemicalHandler, Direction> CHEMICAL = BlockCapability.createSided(
            ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"), IChemicalHandler.class);

    private MekanismChemicals() {
    }

    /** True if the block at pos offers chemicals on that side. Not cached: only for connecting pipes. */
    public static boolean hasHandler(Level level, BlockPos pos, Direction side) {
        return level.getCapability(CHEMICAL, pos, side) != null;
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
}
