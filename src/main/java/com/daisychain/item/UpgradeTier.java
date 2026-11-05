package com.daisychain.item;

/** Pipe upgrade tiers. Level 0 means "no upgrade". The level is what gets saved in pipes. */
public enum UpgradeTier {
    NONE(0, "none"),
    IRON(1, "iron"),
    GOLD(2, "gold"),
    DIAMOND(3, "diamond"),
    NETHERITE(4, "netherite"),
    NETHER_STAR(5, "nether_star");

    private static final UpgradeTier[] BY_LEVEL = values();
    private final int level;
    private final String name;

    UpgradeTier(int level, String name) {
        this.level = level;
        this.name = name;
    }

    public int level() {
        return level;
    }

    /** Used for item ids ("iron_upgrade") and translation keys. */
    public String id() {
        return name;
    }

    public static UpgradeTier byLevel(int level) {
        return level >= 0 && level < BY_LEVEL.length ? BY_LEVEL[level] : NONE;
    }
}
