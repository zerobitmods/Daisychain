package com.daisychain.compat.mekanism;

import com.daisychain.compat.ChemicalOps;
import com.daisychain.compat.Chemicals;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Step 178: the same capability on items (chemical tanks, jetpacks...), reached the same way: NeoForge returns the
     * item capability Mekanism made under this name.
     */
    static final ItemCapability<IChemicalHandler, Void> CHEMICAL_ITEM = ItemCapability.createVoid(
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

    // ---------------------------------------------------------------- step 180: pipes Mekanism's tubes connect to

    /**
     * Registers a chemical handler that holds and takes nothing on these pipes, on every side (none for no side). Per
     * block, not per block entity: bare pipes have none, and Mekanism looks the capability up by position. Mekanism's
     * pressurized tubes then see the pipe as something to connect to (they connect, and take chemicals, only on such
     * sides). Daisychain's own pipes never connect to each other through it (pipe neighbors are handled first).
     */
    public static void offerPipeHandlers(RegisterCapabilitiesEvent event, Block... pipes) {
        event.registerBlock(CHEMICAL, (level, pos, state, blockEntity, side) -> side == null ? null : NoChemicals.INSTANCE,
                pipes);
    }

    /** Step 180: no tanks; refuses every insert, gives nothing. */
    private static final class NoChemicals implements IChemicalHandler {
        static final NoChemicals INSTANCE = new NoChemicals();

        @Override
        public int getChemicalTanks() {
            return 0;
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
            return 0;
        }

        @Override
        public boolean isValid(int tank, ChemicalStack stack) {
            return false;
        }

        @Override
        public ChemicalStack insertChemical(int tank, ChemicalStack stack, Action action) {
            return stack;
        }

        @Override
        public ChemicalStack extractChemical(int tank, long amount, Action action) {
            return ChemicalStack.EMPTY;
        }
    }

    // ---------------------------------------------------------------- step 178: filters

    public static boolean exists(ResourceLocation id) {
        return !MekanismAPI.EMPTY_CHEMICAL_KEY.location().equals(id) && MekanismAPI.CHEMICAL_REGISTRY.containsKey(id);
    }

    @Nullable
    public static Object byId(ResourceLocation id) {
        return exists(id) ? MekanismAPI.CHEMICAL_REGISTRY.get(id) : null;
    }

    public static Object tagKey(ResourceLocation tag) {
        return TagKey.create(MekanismAPI.CHEMICAL_REGISTRY_NAME, tag);
    }

    public static boolean tagExists(ResourceLocation tag) {
        return MekanismAPI.CHEMICAL_REGISTRY.getTag(TagKey.create(MekanismAPI.CHEMICAL_REGISTRY_NAME, tag)).isPresent();
    }

    public static List<String> tagsOf(ResourceLocation id) {
        List<String> tags = new ArrayList<>();
        if (exists(id)) {
            MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder(MekanismAPI.CHEMICAL_REGISTRY.get(id)).tags().forEach(tag -> tags.add(tag.location().toString()));
        }
        return tags;
    }

    public static List<String> allTagNames() {
        List<String> tags = new ArrayList<>();
        MekanismAPI.CHEMICAL_REGISTRY.getTagNames().forEach(tag -> tags.add(tag.location().toString()));
        return tags;
    }

    @Nullable
    public static ResourceLocation idInItem(ItemStack stack) {
        IChemicalHandler handler = stack.getCapability(CHEMICAL_ITEM);
        if (handler == null) {
            return null;
        }
        for (int i = 0; i < handler.getChemicalTanks(); i++) {
            ChemicalStack inTank = handler.getChemicalInTank(i);
            if (!inTank.isEmpty()) {
                return MekanismAPI.CHEMICAL_REGISTRY.getKey(inTank.getChemical());
            }
        }
        return null;
    }

    @Nullable
    public static ResourceLocation idOfIngredient(Object ingredient) {
        if (ingredient instanceof ChemicalStack stack && !stack.isEmpty()) {
            return MekanismAPI.CHEMICAL_REGISTRY.getKey(stack.getChemical());
        }
        return null;
    }

    @Nullable
    public static Component name(ResourceLocation id) {
        return exists(id) ? MekanismAPI.CHEMICAL_REGISTRY.get(id).getTextComponent() : null;
    }

    @Nullable
    public static Chemicals.Look look(ResourceLocation id) {
        if (!exists(id)) {
            return null;
        }
        Chemical chemical = MekanismAPI.CHEMICAL_REGISTRY.get(id);
        return new Chemicals.Look(chemical.getIcon(), chemical.getTint());
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

        @SuppressWarnings("unchecked")
        @Override
        public boolean hasTag(Object chemical, Object tag) {
            // The registry's own holder for a registered chemical (a map lookup, no allocation), then a set lookup.
            return MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder((Chemical) chemical).is((TagKey<Chemical>) tag);
        }

        @Override
        public String modId(Object chemical) {
            ResourceLocation id = MekanismAPI.CHEMICAL_REGISTRY.getKey((Chemical) chemical);
            return id == null ? "" : id.getNamespace();
        }
    }
}
