package com.daisychain.config;

import com.daisychain.block.PipeKind;

/**
 * Amount per transfer and ticks between transfers for each upgrade level (0 = no upgrade), from the server config.
 * Items count items; fluids count mB.
 */
public final class UpgradeSpeeds {
    private UpgradeSpeeds() {
    }

    public static int items(int level) {
        return switch (level) {
            case 1 -> DaisychainConfig.IRON_ITEMS.get();
            case 2 -> DaisychainConfig.GOLD_ITEMS.get();
            case 3 -> DaisychainConfig.DIAMOND_ITEMS.get();
            case 4 -> DaisychainConfig.NETHERITE_ITEMS.get();
            case 5 -> DaisychainConfig.NETHER_STAR_ITEMS.get();
            default -> DaisychainConfig.ITEMS_PER_TRANSFER.get();
        };
    }

    public static int interval(int level) {
        return switch (level) {
            case 1 -> DaisychainConfig.IRON_INTERVAL.get();
            case 2 -> DaisychainConfig.GOLD_INTERVAL.get();
            case 3 -> DaisychainConfig.DIAMOND_INTERVAL.get();
            case 4 -> DaisychainConfig.NETHERITE_INTERVAL.get();
            case 5 -> DaisychainConfig.NETHER_STAR_INTERVAL.get();
            default -> DaisychainConfig.ITEM_TRANSFER_INTERVAL.get();
        };
    }

    /** Amount per transfer for this kind of pipe: items, or mB of fluid. */
    public static int amount(PipeKind kind, int level) {
        return switch (kind) {
            case FLUID -> fluidMb(level);
            case ENERGY -> energyFe(level);
            default -> items(level); // ITEM (UNIVERSAL asks per resource: amount(ofResource(r), level))
        };
    }

    /** Ticks between transfers for this kind of pipe. */
    public static int interval(PipeKind kind, int level) {
        return switch (kind) {
            case FLUID -> fluidInterval(level);
            case ENERGY -> energyInterval(level);
            // A universal side wakes as often as its fastest resource; each resource still waits its own interval.
            case UNIVERSAL -> Math.min(interval(level), Math.min(fluidInterval(level), energyInterval(level)));
            default -> interval(level);
        };
    }

    public static int fluidMb(int level) {
        return level >= 1 && level <= 5 ? DaisychainConfig.FLUID_UPGRADE_MB[level].get()
                : DaisychainConfig.FLUID_MB_PER_TRANSFER.get();
    }

    public static int fluidInterval(int level) {
        return level >= 1 && level <= 5 ? DaisychainConfig.FLUID_UPGRADE_INTERVAL[level].get()
                : DaisychainConfig.FLUID_TRANSFER_INTERVAL.get();
    }

    public static int energyFe(int level) {
        return level >= 1 && level <= 5 ? DaisychainConfig.ENERGY_UPGRADE_FE[level].get()
                : DaisychainConfig.ENERGY_FE_PER_TRANSFER.get();
    }

    public static int energyInterval(int level) {
        return level >= 1 && level <= 5 ? DaisychainConfig.ENERGY_UPGRADE_INTERVAL[level].get()
                : DaisychainConfig.ENERGY_TRANSFER_INTERVAL.get();
    }

    /** True once the server config is available (always on the server; on clients after joining a world). */
    public static boolean available() {
        return DaisychainConfig.SERVER_SPEC.isLoaded();
    }
}
