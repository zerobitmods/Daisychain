package com.daisychain.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config: config/daisychain-server.toml in the game folder (a world's serverconfig/ folder can hold an override
 * copy). Edits reload while the game runs. Pack makers tune these.
 */
public final class DaisychainConfig {
    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.IntValue ITEM_TRANSFER_INTERVAL;
    public static final ModConfigSpec.IntValue ITEMS_PER_TRANSFER;

    public static final ModConfigSpec.IntValue IRON_ITEMS;
    public static final ModConfigSpec.IntValue IRON_INTERVAL;
    public static final ModConfigSpec.IntValue GOLD_ITEMS;
    public static final ModConfigSpec.IntValue GOLD_INTERVAL;
    public static final ModConfigSpec.IntValue DIAMOND_ITEMS;
    public static final ModConfigSpec.IntValue DIAMOND_INTERVAL;
    public static final ModConfigSpec.IntValue NETHERITE_ITEMS;
    public static final ModConfigSpec.IntValue NETHERITE_INTERVAL;
    public static final ModConfigSpec.IntValue NETHER_STAR_ITEMS;
    public static final ModConfigSpec.IntValue NETHER_STAR_INTERVAL;

    public static final ModConfigSpec.IntValue FLUID_TRANSFER_INTERVAL;
    public static final ModConfigSpec.IntValue FLUID_MB_PER_TRANSFER;
    /** Fluid speed per upgrade level 1..5 (index 0 unused): mB per transfer and ticks between transfers. */
    public static final ModConfigSpec.IntValue[] FLUID_UPGRADE_MB = new ModConfigSpec.IntValue[6];
    public static final ModConfigSpec.IntValue[] FLUID_UPGRADE_INTERVAL = new ModConfigSpec.IntValue[6];

    public static final ModConfigSpec.IntValue ENERGY_TRANSFER_INTERVAL;
    public static final ModConfigSpec.IntValue ENERGY_FE_PER_TRANSFER;
    /** Energy speed per upgrade level 1..5 (index 0 unused): FE per transfer and ticks between transfers. */
    public static final ModConfigSpec.IntValue[] ENERGY_UPGRADE_FE = new ModConfigSpec.IntValue[6];
    public static final ModConfigSpec.IntValue[] ENERGY_UPGRADE_INTERVAL = new ModConfigSpec.IntValue[6];

    public static final ModConfigSpec.IntValue MAX_SOURCE_SLOTS_SCANNED;
    public static final ModConfigSpec.IntValue MAX_HANDLER_CALLS_PER_TRANSFER;
    public static final ModConfigSpec.IntValue MAX_HANDLER_CALLS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_SLOTS_COUNTED;
    public static final ModConfigSpec.DoubleValue MAX_MILLISECONDS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_IDLE_BACKOFF;
    public static final ModConfigSpec.IntValue MAX_EMPTY_WAIT_TICKS;
    public static final ModConfigSpec.IntValue BLOCKED_WAIT_TICKS;
    public static final ModConfigSpec.IntValue MAX_NETWORK_PASTE_SIDES;

    private static final int MAX_ITEMS = 1_000_000;
    private static final int MAX_INTERVAL = 1200;
    private static final int MAX_MB = 1_000_000_000;
    private static final String[] TIER_NAMES = {"", "iron", "gold", "diamond", "netherite", "nether_star"};
    /** Default fluid speeds per level (0 = no upgrade): mB per transfer and ticks between transfers. */
    private static final int[] DEFAULT_FLUID_MB = {5_000, 20_000, 40_000, 80_000, 160_000, 1_280_000};
    private static final int[] DEFAULT_FLUID_INTERVAL = {10, 10, 5, 2, 1, 1};
    /** Most FE one transfer can move: the largest number an energy handler call accepts. */
    private static final int MAX_FE = Integer.MAX_VALUE;
    /** Default energy speeds per level (0 = no upgrade): FE per transfer and ticks between transfers (every tick). */
    private static final int[] DEFAULT_ENERGY_FE = {20_000, 100_000, 500_000, 2_500_000, 16_000_000, MAX_FE};
    private static final int[] DEFAULT_ENERGY_INTERVAL = {1, 1, 1, 1, 1, 1};

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Item pipe speed with no upgrade").push("items");
        ITEM_TRANSFER_INTERVAL = builder
                .comment("Ticks between transfers for each pulling side. 20 ticks = 1 second.")
                .defineInRange("transfer_interval_ticks", 20, 1, MAX_INTERVAL);
        ITEMS_PER_TRANSFER = builder
                .comment("Maximum items moved per pulling side per transfer.")
                .defineInRange("items_per_transfer", 8, 1, MAX_ITEMS);
        builder.pop();

        builder.comment("Speed of each upgrade tier: items per transfer, and ticks between transfers").push("upgrades");
        builder.push("iron");
        IRON_ITEMS = builder.defineInRange("items_per_transfer", 16, 1, MAX_ITEMS);
        IRON_INTERVAL = builder.defineInRange("transfer_interval_ticks", 10, 1, MAX_INTERVAL);
        builder.pop();
        builder.push("gold");
        GOLD_ITEMS = builder.defineInRange("items_per_transfer", 32, 1, MAX_ITEMS);
        GOLD_INTERVAL = builder.defineInRange("transfer_interval_ticks", 5, 1, MAX_INTERVAL);
        builder.pop();
        builder.push("diamond");
        DIAMOND_ITEMS = builder.defineInRange("items_per_transfer", 64, 1, MAX_ITEMS);
        DIAMOND_INTERVAL = builder.defineInRange("transfer_interval_ticks", 2, 1, MAX_INTERVAL);
        builder.pop();
        builder.push("netherite");
        NETHERITE_ITEMS = builder.defineInRange("items_per_transfer", 64, 1, MAX_ITEMS);
        NETHERITE_INTERVAL = builder.defineInRange("transfer_interval_ticks", 1, 1, MAX_INTERVAL);
        builder.pop();
        builder.push("nether_star");
        NETHER_STAR_ITEMS = builder.defineInRange("items_per_transfer", 4096, 1, MAX_ITEMS);
        NETHER_STAR_INTERVAL = builder.defineInRange("transfer_interval_ticks", 1, 1, MAX_INTERVAL);
        builder.pop();
        builder.pop();

        builder.comment("Fluid pipe speed with no upgrade. 1000 mB = 1 bucket.").push("fluids");
        FLUID_TRANSFER_INTERVAL = builder
                .comment("Ticks between transfers for each pulling side. 20 ticks = 1 second.")
                .defineInRange("transfer_interval_ticks", DEFAULT_FLUID_INTERVAL[0], 1, MAX_INTERVAL);
        FLUID_MB_PER_TRANSFER = builder
                .comment("Maximum fluid moved per pulling side per transfer, in mB.")
                .defineInRange("mb_per_transfer", DEFAULT_FLUID_MB[0], 1, MAX_MB);
        builder.pop();

        builder.comment("Fluid pipe speed of each upgrade tier: mB per transfer, and ticks between transfers",
                "(the same upgrade items as item pipes)").push("fluid_upgrades");
        for (int level = 1; level <= 5; level++) {
            builder.push(TIER_NAMES[level]);
            FLUID_UPGRADE_MB[level] = builder.defineInRange("mb_per_transfer", DEFAULT_FLUID_MB[level], 1, MAX_MB);
            FLUID_UPGRADE_INTERVAL[level] = builder.defineInRange("transfer_interval_ticks",
                    DEFAULT_FLUID_INTERVAL[level], 1, MAX_INTERVAL);
            builder.pop();
        }
        builder.pop();

        builder.comment("Speed of the basic energy pipe (and of a universal pipe's energy with no upgrade), in FE (Forge Energy).",
                "Energy moves every tick by default, because many machines only accept a limited amount per call.").push("energy");
        ENERGY_TRANSFER_INTERVAL = builder
                .comment("Ticks between transfers for each pulling side. 1 = every tick.")
                .defineInRange("transfer_interval_ticks", DEFAULT_ENERGY_INTERVAL[0], 1, MAX_INTERVAL);
        ENERGY_FE_PER_TRANSFER = builder
                .comment("Maximum energy moved per pulling side per transfer, in FE.")
                .defineInRange("fe_per_transfer", DEFAULT_ENERGY_FE[0], 1, MAX_FE);
        builder.pop();

        builder.comment("Speed of each energy pipe tier (iron to nether star energy pipes), and of a universal pipe's energy with",
                "that upgrade: FE per transfer, and ticks between transfers. Energy pipes take no upgrades; on a network",
                "with mixed tiers, energy moves at the speed of the slowest pipe on its route.").push("energy_upgrades");
        for (int level = 1; level <= 5; level++) {
            builder.push(TIER_NAMES[level]);
            ENERGY_UPGRADE_FE[level] = builder.defineInRange("fe_per_transfer", DEFAULT_ENERGY_FE[level], 1, MAX_FE);
            ENERGY_UPGRADE_INTERVAL[level] = builder.defineInRange("transfer_interval_ticks",
                    DEFAULT_ENERGY_INTERVAL[level], 1, MAX_INTERVAL);
            builder.pop();
        }
        builder.pop();

        builder.comment("Safety limits that cap the work one transfer can do, whatever the speed").push("limits");
        MAX_SOURCE_SLOTS_SCANNED = builder
                .comment("Most source slots (or fluid tanks) looked at per transfer. Large storages are scanned a part",
                        "at a time.")
                .defineInRange("max_source_slots_scanned", 128, 1, 4096);
        MAX_HANDLER_CALLS_PER_TRANSFER = builder
                .comment("Most calls into other blocks' inventories per transfer. Stops runaway work.",
                        "Moving one stack to one destination costs about 4 calls.")
                .defineInRange("max_handler_calls_per_transfer", 1024, 8, 100000);
        MAX_SLOTS_COUNTED = builder
                .comment("\"Keep in source\" and \"Fill destination to\" have to count how many of an item an inventory",
                        "holds. Inventories with more slots than this aren't counted: a source's items are left alone",
                        "(never over-pulled) and a destination gets nothing (never overfilled).")
                .defineInRange("max_slots_counted", 512, 9, 100000);
        MAX_HANDLER_CALLS_PER_TICK = builder
                .comment("Most calls into other blocks' inventories per tick, for all pipes in one dimension together.",
                        "When it runs out, the remaining pipes wait and go first next tick. Protects the server",
                        "if players build far more fast pipes than it can handle.")
                .defineInRange("max_handler_calls_per_tick", 20000, 64, 100_000_000);
        MAX_MILLISECONDS_PER_TICK = builder
                .comment("Most time Daisychain's item transfers may use per tick in one dimension, in milliseconds.",
                        "A tick is 50 ms. Same waiting rule as above. Catches slow inventories from other mods.")
                .defineInRange("max_milliseconds_per_tick", 10.0, 0.1, 50.0);
        builder.pop();

        builder.comment("Idle behaviour").push("idle");
        MAX_IDLE_BACKOFF = builder
                .comment("When a pulling side has items but no destination accepts them (everything full), it waits",
                        "longer each time, up to this many times the normal interval.",
                        "Any change to its pipes wakes it immediately.")
                .defineInRange("max_backoff_multiplier", 4, 1, 64);
        MAX_EMPTY_WAIT_TICKS = builder
                .comment("When a pulling side finds nothing to pull (empty source, or nothing its filter wants), it",
                        "checks less often, but never waits longer than this many ticks (or its normal interval, if",
                        "that is longer). Items that show up are picked up within this time. Lower = quicker to react,",
                        "higher = cheaper on servers with many idle pipes. 20 ticks = 1 second.")
                .defineInRange("max_empty_wait_ticks", 10, 1, 1200);
        BLOCKED_WAIT_TICKS = builder
                .comment("Item pipes only. When a pulling side has items but every destination is full, it slows down",
                        "until it waits at least this many ticks between tries (or max_backoff_multiplier times its",
                        "interval, if that is longer). Without it the fastest upgrades check a full storage again every",
                        "4 ticks, and every check looks at every slot of every destination. Lower = quicker to notice a",
                        "freed slot, higher = cheaper while storage is full. 0 = off. 20 ticks = 1 second.")
                .defineInRange("blocked_wait_ticks", 20, 0, 1200);
        builder.pop();

        builder.comment("Settings Copier").push("copier");
        MAX_NETWORK_PASTE_SIDES = builder
                .comment("Most sides one Settings Copier paste may change: a paste onto a whole network (sneak +",
                        "right-click a pipe's center) with more sides than this is refused. Also the most sides one",
                        "undo remembers. Each remembered side keeps a copy of its settings in memory until the player's",
                        "next paste or logout.")
                .defineInRange("max_network_paste_sides", 1024, 1, 100000);
        builder.pop();

        SERVER_SPEC = builder.build();
    }

    private DaisychainConfig() {
    }
}
