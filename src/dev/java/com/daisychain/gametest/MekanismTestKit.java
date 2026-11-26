package com.daisychain.gametest;

import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Step 172 (game tests only, and only called when Mekanism is loaded): fills and reads Mekanism chemical tanks through
 * Mekanism's api, and gives barrels a chemical handler that takes chemicals in simulation but throws when they really
 * arrive (to check that a throwing handler switches gas off without losing anything).
 */
final class MekanismTestKit {
    private static final BlockCapability<IChemicalHandler, Direction> CHEMICAL = BlockCapability.createSided(
            ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"), IChemicalHandler.class);

    /**
     * The barrel handler only throws while its own test batch runs (GasPipeTests sets this): a barrel left behind in the
     * test world (a failed run) then takes nothing instead of switching gas off for every later test.
     */
    static volatile boolean throwingArmed;

    private MekanismTestKit() {
    }

    private static IChemicalHandler handler(ServerLevel level, BlockPos pos) {
        // Through a side (the test tanks have every chemical side on): Mekanism's side-less view is read only.
        IChemicalHandler handler = level.getCapability(CHEMICAL, pos, Direction.UP);
        if (handler == null) {
            throw new IllegalStateException("No chemical handler at " + pos);
        }
        return handler;
    }

    /** Puts mB of a chemical ("mekanism:hydrogen") into the block at pos; returns how much went in. */
    static long fill(ServerLevel level, BlockPos pos, String chemicalId, long amount) {
        Chemical chemical = MekanismAPI.CHEMICAL_REGISTRY.get(ResourceLocation.parse(chemicalId));
        ChemicalStack left = handler(level, pos).insertChemical(new ChemicalStack(chemical, amount), Action.EXECUTE);
        return amount - left.getAmount();
    }

    /** mB of chemicals in all the block's tanks. */
    static long amount(ServerLevel level, BlockPos pos) {
        IChemicalHandler handler = handler(level, pos);
        long total = 0;
        for (int i = 0; i < handler.getChemicalTanks(); i++) {
            total += handler.getChemicalInTank(i).getAmount();
        }
        return total;
    }

    /** Puts mB of an infuse type ("mekanism:redstone") in: on 1.21.1 it is one of the chemicals. */
    static long fillInfusion(ServerLevel level, BlockPos pos, String id, long amount) {
        return fill(level, pos, id, amount);
    }

    /** mB in all the block's chemical tanks (1.21.1 keeps infuse types with the other chemicals). */
    static long amountInfusion(ServerLevel level, BlockPos pos) {
        return amount(level, pos);
    }

    private static final ItemCapability<IChemicalHandler, Void> CHEMICAL_ITEM = ItemCapability.createVoid(
            ResourceLocation.fromNamespaceAndPath("mekanism", "chemical_handler"), IChemicalHandler.class);

    /** Step 178: a Basic Chemical Tank item holding mB of a chemical (0 = an empty tank item). */
    static ItemStack tankItem(String chemicalId, long amount) {
        return chemicalItem("mekanism:basic_chemical_tank", chemicalId, amount);
    }

    /** Step 178: any Mekanism item that holds chemicals (jetpack, scuba tank...) holding mB of a chemical. */
    static ItemStack chemicalItem(String itemId, String chemicalId, long amount) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId)));
        if (amount > 0) {
            IChemicalHandler handler = stack.getCapability(CHEMICAL_ITEM);
            if (handler == null) {
                throw new IllegalStateException(itemId + " has no chemical handler");
            }
            Chemical chemical = MekanismAPI.CHEMICAL_REGISTRY.get(ResourceLocation.parse(chemicalId));
            handler.insertChemical(new ChemicalStack(chemical, amount), Action.EXECUTE);
        }
        return stack;
    }

    /** Step 178: what JEI hands over when a chemical is dragged from Mekanism's list (a ChemicalStack). */
    static Object jeiIngredient(String chemicalId) {
        return new ChemicalStack(MekanismAPI.CHEMICAL_REGISTRY.get(ResourceLocation.parse(chemicalId)), 1_000);
    }

    /** Barrels: a chemical handler that accepts anything in simulation and throws on a real insert. */
    static void registerThrowingBarrel(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(CHEMICAL, BlockEntityType.BARREL, (barrel, side) -> new ThrowingHandler());
    }

    private static final class ThrowingHandler implements IChemicalHandler {
        @Override
        public int getChemicalTanks() {
            return 1;
        }

        @Override
        public ChemicalStack getChemicalInTank(int tank) {
            return ChemicalStack.EMPTY;
        }

        @Override
        public void setChemicalInTank(int tank, ChemicalStack stack) {
        }

        @Override
        public long getChemicalTankCapacity(int tank) {
            return 1_000_000;
        }

        @Override
        public boolean isValid(int tank, ChemicalStack stack) {
            return true;
        }

        @Override
        public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
            if (!throwingArmed) {
                return stack; // takes nothing
            }
            if (action.execute()) {
                throw new IllegalStateException("test handler: throws on a real insert");
            }
            return ChemicalStack.EMPTY;
        }

        @Override
        public ChemicalStack extractChemical(int tank, long amount, Action action) {
            return ChemicalStack.EMPTY;
        }
    }
}
