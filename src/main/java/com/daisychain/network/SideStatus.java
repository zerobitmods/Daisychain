package com.daisychain.network;

/**
 * Step 108 (zerobit): what a pull side did the last time it ran, so players can see why something isn't moving (Pull
 * side screen and Jade). Stored as one byte per side per resource in Extractor; written where the side runs anyway, so
 * it costs nothing extra per tick. The numbers are sent to clients, so never renumber them.
 */
public final class SideStatus {
    /** Hasn't run yet (just placed, settings just changed, or the world just loaded). */
    public static final int STARTING = 0;
    /** Moved something last time. */
    public static final int MOVING = 1;
    /** The source has something, but no destination took it (full, filtered, at a limit, or held by Keep in source). */
    public static final int WAITING = 2;
    /** The source is empty, or has nothing the filter lets through. */
    public static final int NOTHING_TO_PULL = 3;
    /** No inventory, tank or battery on this side. */
    public static final int NO_SOURCE = 4;
    /** The network has no insert sides. */
    public static final int NO_DESTINATION = 5;
    /** The Redstone setting keeps this side off. */
    public static final int REDSTONE_OFF = 6;
    /** Redstone setting "once per pulse": waiting for the next pulse. */
    public static final int WAITING_FOR_PULSE = 7;
    /** Pulling this resource is switched off on this side (Universal pipe power key). Worked out when asked. */
    public static final int SWITCHED_OFF = 8;

    /** Names for lang keys: screen.daisychain.status.<name> and .<name>.help. Indexed by the numbers above. */
    private static final String[] NAMES = {"starting", "moving", "waiting", "nothing_to_pull", "no_source",
            "no_destination", "redstone_off", "waiting_for_pulse", "switched_off"};

    /**
     * Which status wins when a Universal pipe side shows one line for all its resources (Jade): the most useful news
     * first. Higher = shown first.
     */
    private static final int[] RANK = {0, 8, 7, 4, 3, 6, 5, 5, 1};

    /** Dot / text color per status (RGB): green moving, amber waiting, grey idle, red off, violet pulse. */
    private static final int[] COLORS = {0x5F646E, 0x6CC07E, 0xE0B25A, 0x8D929C, 0x8D929C, 0xE0B25A, 0xD46A62,
            0xA48DF2, 0xD46A62};

    private SideStatus() {
    }

    /** RGB color for a status (screens and Jade). */
    public static int color(int status) {
        return COLORS[valid(status)];
    }

    public static String name(int status) {
        return NAMES[valid(status)];
    }

    /** Clamps numbers from the network or old data to a known status. */
    public static int valid(int status) {
        return status >= 0 && status < NAMES.length ? status : STARTING;
    }

    /** The more useful of two statuses (see RANK). */
    static int better(int a, int b) {
        return RANK[valid(b)] > RANK[valid(a)] ? b : a;
    }
}
