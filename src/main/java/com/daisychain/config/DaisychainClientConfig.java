package com.daisychain.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Per-player settings (config/daisychain-client.toml on each player's own computer). Servers never see these.
 * Also editable from the mod list's Config button.
 */
public final class DaisychainClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_HINTS;
    public static final ModConfigSpec.BooleanValue SIDES_VIEW_3D;
    public static final ModConfigSpec.BooleanValue OPEN_ADVANCED;
    public static final ModConfigSpec.BooleanValue WRENCH_HOLOGRAMS;
    public static final ModConfigSpec.BooleanValue ENERGY_GLOW;
    /** Step 147: pipe colours per pipe type (item, fluid, universal) the body and middle-line colour, "#rrggbb", empty = default. */
    public static final ModConfigSpec.ConfigValue<String>[] PIPE_BODY;
    public static final ModConfigSpec.ConfigValue<String>[] PIPE_LINE;
    /** The pipe types with player colours, in PIPE_BODY / PIPE_LINE order. */
    public static final String[] COLOUR_KINDS = {"item", "fluid", "universal"};

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
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
        builder.comment("Pipe colours. Only on your screen: other players see their own. \"#rrggbb\", or empty for the",
                "default look. Easiest from the Pipe colours screen (mod list > Daisychain > Config).").push("pipe_colours");
        @SuppressWarnings("unchecked")
        ModConfigSpec.ConfigValue<String>[] body = new ModConfigSpec.ConfigValue[COLOUR_KINDS.length];
        @SuppressWarnings("unchecked")
        ModConfigSpec.ConfigValue<String>[] line = new ModConfigSpec.ConfigValue[COLOUR_KINDS.length];
        for (int i = 0; i < COLOUR_KINDS.length; i++) {
            String kind = COLOUR_KINDS[i];
            body[i] = builder.comment("The " + kind + " pipe's body colour.")
                    .translation("daisychain.configuration." + kind + "_body")
                    .define(kind + "_body", "", DaisychainClientConfig::isColour);
            line[i] = builder.comment("The colour of the line along the " + kind + " pipe.")
                    .translation("daisychain.configuration." + kind + "_line")
                    .define(kind + "_line", "", DaisychainClientConfig::isColour);
        }
        builder.pop();
        PIPE_BODY = body;
        PIPE_LINE = line;
        SPEC = builder.build();
    }

    private DaisychainClientConfig() {
    }

    /** "" or "#rrggbb" (the # optional). */
    private static boolean isColour(Object value) {
        return value instanceof String s && (s.isEmpty() || parseColour(s) >= 0);
    }

    /** "#rrggbb" or "rrggbb" -> 0xRRGGBB; anything else (also "") -> -1. */
    public static int parseColour(String s) {
        String hex = s == null ? "" : s.strip();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** A pipe colour setting (index in COLOUR_KINDS; line = the middle line): 0xRRGGBB, or -1 = default. */
    public static int pipeColour(int kind, boolean line) {
        if (!SPEC.isLoaded() || kind < 0 || kind >= COLOUR_KINDS.length) {
            return -1;
        }
        return parseColour((line ? PIPE_LINE : PIPE_BODY)[kind].get());
    }

    /** Saves a pipe colour (-1 = default). Client only. */
    public static void setPipeColour(int kind, boolean line, int rgb) {
        if (!SPEC.isLoaded() || kind < 0 || kind >= COLOUR_KINDS.length) {
            return;
        }
        ModConfigSpec.ConfigValue<String> value = (line ? PIPE_LINE : PIPE_BODY)[kind];
        value.set(rgb < 0 ? "" : String.format("#%06x", rgb & 0xFFFFFF));
        value.save();
    }

    /** True if hints should show. Safe to call before the config has loaded (then: show them). */
    public static boolean showHints() {
        return !SPEC.isLoaded() || SHOW_HINTS.getAsBoolean();
    }

    /** True if pipe side screens open in Advanced view when the pipe has no stored view. */
    public static boolean defaultAdvanced() {
        return SPEC.isLoaded() && OPEN_ADVANCED.getAsBoolean();
    }

    /** Step 72: false if the player turned the wrench holograms (and their click area) off. */
    public static boolean wrenchHolograms() {
        return !SPEC.isLoaded() || WRENCH_HOLOGRAMS.getAsBoolean();
    }

    /** Flips the wrench hologram setting, saves it and returns the new value (sneak + right-click with the Bloom Wrench). */
    public static boolean toggleWrenchHolograms() {
        if (!SPEC.isLoaded()) {
            return true;
        }
        boolean on = !WRENCH_HOLOGRAMS.getAsBoolean();
        WRENCH_HOLOGRAMS.set(on);
        WRENCH_HOLOGRAMS.save();
        return on;
    }

    /** Step 90: true if the line along energy pipes glows. Safe before the config has loaded (then: glow). */
    public static boolean energyGlow() {
        return !SPEC.isLoaded() || ENERGY_GLOW.getAsBoolean();
    }

    /** True if the Pipe sides screen should open in the 3D view. */
    public static boolean sidesView3d() {
        return SPEC.isLoaded() && SIDES_VIEW_3D.getAsBoolean();
    }

    /** Remembers which Pipe sides view the player used last. Client only. */
    public static void setSidesView3d(boolean value) {
        if (SPEC.isLoaded() && SIDES_VIEW_3D.getAsBoolean() != value) {
            SIDES_VIEW_3D.set(value);
            SIDES_VIEW_3D.save();
        }
    }

    /** Flips the hint setting and saves it to disk. Client only. */
    public static void toggleHints() {
        if (SPEC.isLoaded()) {
            SHOW_HINTS.set(!SHOW_HINTS.getAsBoolean());
            SHOW_HINTS.save();
        }
    }
}
