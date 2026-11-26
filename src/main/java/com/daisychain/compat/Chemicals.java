package com.daisychain.compat;

import com.daisychain.Daisychain;
import com.daisychain.compat.mekanism.MekanismChemicals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Step 171: what the rest of Daisychain uses for Mekanism chemicals (gas, infusion, pigment, slurry). Never names a
 * Mekanism class itself; it calls {@link MekanismChemicals} only while Mekanism is loaded and gas is still on.
 * <p>
 * If Mekanism's hookup breaks (a Mekanism update moved or changed what we use, or one of its handlers throws), gas
 * switches off until the game restarts, with one warning in the log. Gas pipes stay where they are and carry nothing;
 * items, fluids, energy and redstone keep working.
 */
public final class Chemicals {
    private static final boolean MEKANISM = ModList.get().isLoaded("mekanism");
    private static volatile boolean broken;

    private Chemicals() {
    }

    /** Called from the mod's constructor: the startup check runs after every other mod registered capabilities. */
    public static void register(IEventBus modEventBus) {
        if (MEKANISM) {
            modEventBus.addListener(EventPriority.LOWEST, Chemicals::checkHookup);
        }
    }

    /** True if Mekanism is installed: gas pipes are shown and crafted (even if the hookup broke later). */
    public static boolean mekanismLoaded() {
        return MEKANISM;
    }

    /** True if chemicals can move: Mekanism is installed and its hookup hasn't failed. */
    public static boolean available() {
        return MEKANISM && !broken;
    }

    /** True if the block at pos offers chemicals on that side (gas pipes connect to it). */
    public static boolean hasHandler(Level level, BlockPos pos, Direction side) {
        if (!available()) {
            return false;
        }
        try {
            return MekanismChemicals.hasHandler(level, pos, side);
        } catch (LinkageError | RuntimeException e) {
            switchOff("looking for a chemical handler at " + pos.toShortString(), e);
            return false;
        }
    }

    /**
     * Step 172: a cache for the chemical handler of the block at pos (its side), kept by the caller like NeoForge's
     * BlockCapabilityCache (it is one). onValid / onInvalidate as for BlockCapabilityCache.create (may be null). Null if
     * gas is off.
     */
    @Nullable
    public static Object newCache(ServerLevel level, BlockPos pos, Direction side, @Nullable BooleanSupplier onValid,
                                  @Nullable Runnable onInvalidate) {
        if (!available()) {
            return null;
        }
        try {
            return MekanismChemicals.newCache(level, pos, side, onValid, onInvalidate);
        } catch (LinkageError | RuntimeException e) {
            switchOff("looking for a chemical handler at " + pos.toShortString(), e);
            return null;
        }
    }

    /** Step 172: the handler a cache from newCache holds now, or null (none, or gas is off). */
    @Nullable
    public static Object handler(@Nullable Object cache) {
        if (cache == null || !available()) {
            return null;
        }
        try {
            return MekanismChemicals.handler(cache);
        } catch (LinkageError | RuntimeException e) {
            switchOff("reading a chemical handler", e);
            return null;
        }
    }

    /** Step 172: how to work a handler from {@link #handler}; null if gas is off. */
    @Nullable
    public static ChemicalOps<?, ?> ops() {
        if (!available()) {
            return null;
        }
        try {
            return MekanismChemicals.OPS;
        } catch (LinkageError | RuntimeException e) {
            switchOff("setting up chemical transfers", e);
            return null;
        }
    }

    // ---------------------------------------------------------------- step 178: filters (never per transfer)

    /** True if a chemical with this registry name exists here (false without Mekanism, or for the empty chemical). */
    public static boolean exists(ResourceLocation id) {
        if (!available()) {
            return false;
        }
        try {
            return MekanismChemicals.exists(id);
        } catch (LinkageError | RuntimeException e) {
            switchOff("looking up chemical " + id, e);
            return false;
        }
    }

    /** The chemical itself (compared by identity in transfers), or null if it doesn't exist here. For filter rebuilds. */
    @Nullable
    public static Object byId(ResourceLocation id) {
        if (!available()) {
            return null;
        }
        try {
            return MekanismChemicals.byId(id);
        } catch (LinkageError | RuntimeException e) {
            switchOff("looking up chemical " + id, e);
            return null;
        }
    }

    /** A chemical tag key for {@link ChemicalOps#hasTag}, or null if gas is off. For filter rebuilds. */
    @Nullable
    public static Object tagKey(ResourceLocation tag) {
        if (!available()) {
            return null;
        }
        try {
            return MekanismChemicals.tagKey(tag);
        } catch (LinkageError | RuntimeException e) {
            switchOff("making chemical tag #" + tag, e);
            return null;
        }
    }

    /** True if this chemical tag exists here (server check for a filter entry's tag). */
    public static boolean tagExists(ResourceLocation tag) {
        if (!available()) {
            return false;
        }
        try {
            return MekanismChemicals.tagExists(tag);
        } catch (LinkageError | RuntimeException e) {
            switchOff("looking up chemical tag #" + tag, e);
            return false;
        }
    }

    /** The tags of one chemical (as "namespace:path"), for the entry detail page. Empty if none or gas is off. */
    public static List<String> tagsOf(ResourceLocation id) {
        if (!available()) {
            return List.of();
        }
        try {
            return MekanismChemicals.tagsOf(id);
        } catch (LinkageError | RuntimeException e) {
            switchOff("reading the tags of chemical " + id, e);
            return List.of();
        }
    }

    /** Every chemical tag (as "namespace:path"), for the entry detail page's search. Empty if gas is off. */
    public static List<String> allTagNames() {
        if (!available()) {
            return List.of();
        }
        try {
            return MekanismChemicals.allTagNames();
        } catch (LinkageError | RuntimeException e) {
            switchOff("listing chemical tags", e);
            return List.of();
        }
    }

    /** The first chemical an item holds (chemical tank, jetpack...), by registry name; null if none or gas is off. */
    @Nullable
    public static ResourceLocation idInItem(ItemStack stack) {
        if (!available() || stack.isEmpty()) {
            return null;
        }
        try {
            return MekanismChemicals.idInItem(stack);
        } catch (LinkageError | RuntimeException e) {
            switchOff("reading the chemical in " + stack, e);
            return null;
        }
    }

    /** JEI: the chemical a dragged ingredient stands for (Mekanism lists chemicals as their own ingredient), or null. */
    @Nullable
    public static ResourceLocation idOfIngredient(Object ingredient) {
        if (!available()) {
            return null;
        }
        try {
            return MekanismChemicals.idOfIngredient(ingredient);
        } catch (LinkageError | RuntimeException e) {
            switchOff("reading a JEI chemical", e);
            return null;
        }
    }

    /** The chemical's display name, or null if it doesn't exist here. */
    @Nullable
    public static Component name(ResourceLocation id) {
        if (!available()) {
            return null;
        }
        try {
            return MekanismChemicals.name(id);
        } catch (LinkageError | RuntimeException e) {
            switchOff("reading the name of chemical " + id, e);
            return null;
        }
    }

    /**
     * Drawing: the chemical's texture (a block-atlas sprite name) and tint (0xRRGGBB, maybe with alpha), or null if it
     * doesn't exist here.
     */
    @Nullable
    public static Look look(ResourceLocation id) {
        if (!available()) {
            return null;
        }
        try {
            return MekanismChemicals.look(id);
        } catch (LinkageError | RuntimeException e) {
            switchOff("reading the look of chemical " + id, e);
            return null;
        }
    }

    /** How to draw a chemical: a block-atlas texture tinted with a colour. */
    public record Look(ResourceLocation texture, int tint) {
    }

    private static void checkHookup(RegisterCapabilitiesEvent event) {
        try {
            if (!MekanismChemicals.mekanismBlocksOfferIt(event)) {
                switchOff("checking it at startup: no Mekanism block offers \"mekanism:chemical_handler\"", null);
            }
        } catch (LinkageError | RuntimeException e) {
            switchOff("checking it at startup", e);
        }
    }

    /** Turns gas off for the rest of this game, with one warning. */
    public static void switchOff(String what, @javax.annotation.Nullable Throwable cause) {
        if (broken) {
            return;
        }
        broken = true;
        Daisychain.LOGGER.warn("Daisychain: gas pipes are off until the game restarts. Mekanism's chemical hookup failed "
                + "while {}{}. Items, fluids, energy and redstone keep working. A Mekanism update may have changed "
                + "what Daisychain uses; please report this with your Mekanism and Daisychain versions.", what,
                cause == null ? "" : " (" + cause + ")");
    }

    /** Game tests only: pretend the hookup broke (true) or undo it (false). */
    public static void setBrokenForTest(boolean value) {
        broken = value;
    }
}
