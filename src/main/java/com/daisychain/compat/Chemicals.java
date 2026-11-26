package com.daisychain.compat;

import com.daisychain.Daisychain;
import com.daisychain.compat.mekanism.MekanismChemicals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

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
