package com.daisychain.compat;

import com.daisychain.Daisychain;
import com.daisychain.compat.mekanism.MekanismChemicals;
import com.daisychain.filter.ChemicalId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Step F95 (port of step 171): what the rest of Daisychain uses for Mekanism chemicals (gas, infusion, pigment, slurry). Never names a
 * Mekanism class itself; it calls {@link MekanismChemicals} only while Mekanism is loaded and gas is still on.
 * <p>
 * If Mekanism's hookup breaks (a Mekanism update moved or changed what we use, or one of its handlers throws), gas
 * switches off until the game restarts, with one warning in the log. Gas pipes stay where they are and carry nothing;
 * items, fluids, energy and redstone keep working.
 */
public final class Chemicals {
    private static final boolean MEKANISM = ModList.get().isLoaded("mekanism");
    /** Step F96: Mekanism 10.4's chemical types, each with its own handler: gas, infusion, pigment, slurry. */
    public static final int TYPES = 4;
    private static volatile boolean broken;

    private Chemicals() {
    }

    /** Called from the mod's constructor: the startup check runs once loading is complete (capabilities registered). */
    public static void register(IEventBus modEventBus) {
        if (MEKANISM) {
            modEventBus.addListener(Chemicals::checkHookup);
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
     * Step F96 (port of 172): caches for the chemical handlers (all four types) of the block at pos (its side), kept by
     * the caller; release them with {@link #releaseCache}. onValid / onInvalidate as for CapCache.create (may be null).
     * Null if gas is off.
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

    /** Step F96: takes a cache from newCache off its block (Forge listeners). Safe whatever state gas is in. */
    public static void releaseCache(Object cache) {
        try {
            MekanismChemicals.releaseCache(cache);
        } catch (LinkageError | RuntimeException e) {
            switchOff("releasing a chemical handler cache", e);
        }
    }

    /** Step F96: the handler of one type a cache from newCache holds now, or null (none, or gas is off). */
    @Nullable
    public static Object handler(@Nullable Object cache, int type) {
        if (cache == null || !available()) {
            return null;
        }
        try {
            return MekanismChemicals.handler(cache, type);
        } catch (LinkageError | RuntimeException e) {
            switchOff("reading a chemical handler", e);
            return null;
        }
    }

    /** Step F96: how to work a handler of one type from {@link #handler}; null if gas is off. */
    @Nullable
    public static ChemicalOps<?, ?> ops(int type) {
        if (!available()) {
            return null;
        }
        try {
            return MekanismChemicals.ops(type);
        } catch (LinkageError | RuntimeException e) {
            switchOff("setting up chemical transfers", e);
            return null;
        }
    }

    private static void checkHookup(FMLLoadCompleteEvent event) {
        try {
            if (!MekanismChemicals.capabilitiesRegistered()) {
                switchOff("checking it at startup: a chemical capability (gas, infusion, pigment, slurry) isn't registered",
                        null);
            }
        } catch (LinkageError | RuntimeException e) {
            switchOff("checking it at startup", e);
        }
    }

    // ---------------------------------------------------------------- step F102 (port of 178): filters, never per transfer

    /** True if this chemical exists here (false without Mekanism, for the empty chemical or an unknown type). */
    public static boolean exists(ChemicalId id) {
        if (!available() || !id.validType()) {
            return false;
        }
        try {
            return MekanismChemicals.exists(id.type(), id.id());
        } catch (LinkageError | RuntimeException e) {
            switchOff("looking up chemical " + id, e);
            return false;
        }
    }

    /** The chemical itself (compared by identity in transfers), or null if it doesn't exist here. For filter rebuilds. */
    @Nullable
    public static Object byId(ChemicalId id) {
        if (!available() || !id.validType()) {
            return null;
        }
        try {
            return MekanismChemicals.byId(id.type(), id.id());
        } catch (LinkageError | RuntimeException e) {
            switchOff("looking up chemical " + id, e);
            return null;
        }
    }

    /**
     * A tag of one chemical type for {@link ChemicalOps#hasTag} (Forge's tag object, which stays the same across
     * reloads), or null if gas is off. For filter rebuilds.
     */
    @Nullable
    public static Object tagKey(int type, ResourceLocation tag) {
        if (!available() || type < 0 || type >= TYPES) {
            return null;
        }
        try {
            return MekanismChemicals.tag(type, tag);
        } catch (LinkageError | RuntimeException e) {
            switchOff("making chemical tag #" + tag, e);
            return null;
        }
    }

    /** True if this tag of that chemical type exists here (server check for a filter entry's tag). */
    public static boolean tagExists(int type, ResourceLocation tag) {
        if (!available() || type < 0 || type >= TYPES) {
            return false;
        }
        try {
            return MekanismChemicals.tagExists(type, tag);
        } catch (LinkageError | RuntimeException e) {
            switchOff("looking up chemical tag #" + tag, e);
            return false;
        }
    }

    /** The tags of one chemical (as "namespace:path"), for the entry detail page. Empty if none or gas is off. */
    public static List<String> tagsOf(ChemicalId id) {
        if (!available() || !id.validType()) {
            return List.of();
        }
        try {
            return MekanismChemicals.tagsOf(id.type(), id.id());
        } catch (LinkageError | RuntimeException e) {
            switchOff("reading the tags of chemical " + id, e);
            return List.of();
        }
    }

    /** Every tag of one chemical type (as "namespace:path"), for the detail page's search. Empty if gas is off. */
    public static List<String> allTagNames(int type) {
        if (!available() || type < 0 || type >= TYPES) {
            return List.of();
        }
        try {
            return MekanismChemicals.allTagNames(type);
        } catch (LinkageError | RuntimeException e) {
            switchOff("listing chemical tags", e);
            return List.of();
        }
    }

    /** The first chemical an item holds (chemical tank, jetpack...), any type; null if none or gas is off. */
    @Nullable
    public static ChemicalId idInItem(ItemStack stack) {
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

    /** JEI: the chemical a dragged ingredient stands for (Mekanism lists each type as its own ingredient), or null. */
    @Nullable
    public static ChemicalId idOfIngredient(Object ingredient) {
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
    public static Component name(ChemicalId id) {
        if (!available() || !id.validType()) {
            return null;
        }
        try {
            return MekanismChemicals.name(id.type(), id.id());
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
    public static Look look(ChemicalId id) {
        if (!available() || !id.validType()) {
            return null;
        }
        try {
            return MekanismChemicals.look(id.type(), id.id());
        } catch (LinkageError | RuntimeException e) {
            switchOff("reading the look of chemical " + id, e);
            return null;
        }
    }

    /** How to draw a chemical: a block-atlas texture tinted with a colour. */
    public record Look(ResourceLocation texture, int tint) {
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
