package com.daisychain.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Per-player settings (config/daisychain-client.toml on each player's own computer). Servers never see these.
 * Also editable from the mod list's Config button.
 */
public final class DaisychainClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue SHOW_HINTS;
    public static final ForgeConfigSpec.BooleanValue SIDES_VIEW_3D;
    public static final ForgeConfigSpec.BooleanValue OPEN_ADVANCED;
    public static final ForgeConfigSpec.BooleanValue WRENCH_HOLOGRAMS;
    public static final ForgeConfigSpec.BooleanValue ENERGY_GLOW;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SHOW_HINTS = builder
                .comment("Show how-to hints in Daisychain screens (e.g. \"Shift+click: remove\").",
                        "The ? button in the Pull side screen toggles this too.")
                .translation("daisychain.configuration.show_hints")
                .define("show_hints", true);
        SIDES_VIEW_3D = builder
                .comment("Open the Pipe sides screen in the 3D view (true) or the flat cube view (false).",
                        "The button in the top corner of that screen switches it too.")
                .translation("daisychain.configuration.sides_view_3d")
                .define("sides_view_3d", false);
        // Step 108: a true/false setting (was default_menu_mode SIMPLE|ADVANCED): config screens show it as ON/OFF
        // like the others. An old default_menu_mode line in an existing file is no longer read.
        OPEN_ADVANCED = builder
                .comment("Pipe side screens open in Advanced view (54 filter slots in 2 pages and all settings) when",
                        "nobody has picked a view for that pipe yet. false = Simple view (9 filter slots, icons). The",
                        "sliders key in the screen switches a pipe's view for everyone.")
                .translation("daisychain.configuration.open_advanced")
                .define("open_advanced", false);
        WRENCH_HOLOGRAMS = builder
                .comment("While sneaking with a wrench, show a see-through \"ghost arm\" on each side without an arm and let",
                        "it be clicked. Turn off to only change a side by clicking the pipe's actual face on that side.",
                        "Sneak and right-click anything but a pipe with the Bloom Wrench to flip this in game.")
                .translation("daisychain.configuration.wrench_holograms")
                .define("wrench_holograms", true);
        ENERGY_GLOW = builder
                .comment("Step 90: the line along energy pipes glows (stays lit in the dark). Only changes how it looks; it",
                        "costs nothing per frame either way.")
                .translation("daisychain.configuration.energy_glow")
                .define("energy_glow", true);
        SPEC = builder.build();
    }

    private DaisychainClientConfig() {
    }

    /** True if hints should show. Safe to call before the config has loaded (then: show them). */
    public static boolean showHints() {
        return !SPEC.isLoaded() || SHOW_HINTS.get();
    }

    /** True if pipe side screens open in Advanced view when the pipe has no stored view. */
    public static boolean defaultAdvanced() {
        return SPEC.isLoaded() && OPEN_ADVANCED.getAsBoolean();
    }

    /** Step 72: false if the player turned the wrench holograms (and their click area) off. */
    public static boolean wrenchHolograms() {
        return !SPEC.isLoaded() || WRENCH_HOLOGRAMS.get();
    }

    /** Flips the wrench hologram setting, saves it and returns the new value (sneak + right-click with the Bloom Wrench). */
    public static boolean toggleWrenchHolograms() {
        if (!SPEC.isLoaded()) {
            return true;
        }
        boolean on = !WRENCH_HOLOGRAMS.get();
        WRENCH_HOLOGRAMS.set(on);
        WRENCH_HOLOGRAMS.save();
        return on;
    }

    /** Step 90: true if the line along energy pipes glows. Safe before the config has loaded (then: glow). */
    public static boolean energyGlow() {
        return !SPEC.isLoaded() || ENERGY_GLOW.get();
    }

    /** True if the Pipe sides screen should open in the 3D view. */
    public static boolean sidesView3d() {
        return SPEC.isLoaded() && SIDES_VIEW_3D.get();
    }

    /** Remembers which Pipe sides view the player used last. Client only. */
    public static void setSidesView3d(boolean value) {
        if (SPEC.isLoaded() && SIDES_VIEW_3D.get() != value) {
            SIDES_VIEW_3D.set(value);
            SIDES_VIEW_3D.save();
        }
    }

    /** Flips the hint setting and saves it to disk. Client only. */
    public static void toggleHints() {
        if (SPEC.isLoaded()) {
            SHOW_HINTS.set(!SHOW_HINTS.get());
            SHOW_HINTS.save();
        }
    }
}
