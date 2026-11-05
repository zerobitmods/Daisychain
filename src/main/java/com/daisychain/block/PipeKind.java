package com.daisychain.block;

/** What a pipe type carries. Each pipe block has one; pipes of different blocks never connect. */
public enum PipeKind {
    ITEM,
    FLUID,
    ENERGY,
    /** Carries items, fluids and energy at once (step 33); each side has settings per resource. */
    UNIVERSAL;

    /**
     * What a pipe side can carry, as an index into per-resource settings (step 31): items 0, fluids 1, energy 2.
     * Item, fluid and energy pipes carry one resource each; the universal pipe (step 32) carries all three.
     */
    public static final int RESOURCE_COUNT = 3;
    public static final int ITEMS = 0;
    public static final int FLUIDS = 1;
    public static final int ENERGY_RES = 2;
    private static final PipeKind[] BY_RESOURCE = {ITEM, FLUID, ENERGY};

    /** The kind that matches one resource (ITEM, FLUID or ENERGY): decides its units, limits and help texts. */
    public static PipeKind ofResource(int resource) {
        return BY_RESOURCE[resource];
    }

    /** This pipe's own resource, or -1 if it carries several (universal). */
    public int resource() {
        return switch (this) {
            case ITEM -> ITEMS;
            case FLUID -> FLUIDS;
            case ENERGY -> ENERGY_RES;
            case UNIVERSAL -> -1;
        };
    }

    /** True if sides of this pipe carry that resource. */
    public boolean carries(int resource) {
        return resource >= 0 && resource < RESOURCE_COUNT && (this == UNIVERSAL || resource() == resource);
    }

    /** The first resource this pipe carries (what its settings screen opens on). */
    public int firstResource() {
        return Math.max(0, resource());
    }

    /** Largest fluid amount (mB) any number setting can hold: a million buckets. */
    public static final int MAX_FLUID_AMOUNT = 1_000_000_000;
    /** Largest energy amount (FE) any number setting can hold: the most one energy handler call can carry. */
    public static final int MAX_ENERGY_AMOUNT = Integer.MAX_VALUE;
    private static final int MAX_ITEM_BATCH = 1_000_000;
    private static final int MAX_ITEM_STOCK = 100_000;

    /** Item and fluid pipes have the 54-slot filter (items or fluids). Energy is all one kind: no filter. */
    public boolean hasFilter() {
        return this == ITEM || this == FLUID;
    }

    /**
     * Every pipe has the amounts (Batch / Keep in source / Fill destination to / Max stock). Items count items, fluids
     * count mB (step 29), energy counts FE (step 30). Per filter entry only where there is a filter.
     */
    public boolean hasAmounts() {
        return true;
    }

    /** Highest Batch value (0 = Max, following the upgrade). */
    public int maxBatch() {
        return switch (this) {
            case FLUID -> MAX_FLUID_AMOUNT;
            case ENERGY -> MAX_ENERGY_AMOUNT;
            default -> MAX_ITEM_BATCH;
        };
    }

    /** Highest Keep in source / Fill destination to / Max stock value (0 = off). */
    public int maxStock() {
        return switch (this) {
            case FLUID -> MAX_FLUID_AMOUNT;
            case ENERGY -> MAX_ENERGY_AMOUNT;
            default -> MAX_ITEM_STOCK;
        };
    }

    /** One click on a number button: 1 item, 100 mB, or 1,000 FE. */
    public int smallStep() {
        return switch (this) {
            case FLUID -> 100;
            case ENERGY -> 1_000;
            default -> 1;
        };
    }

    /** Shift+click on a number button: 64 items, 1000 mB (one bucket), or 100,000 FE. */
    public int bigStep() {
        return switch (this) {
            case FLUID -> 1_000;
            case ENERGY -> 100_000;
            default -> 64;
        };
    }

    /** Suffix of this kind's own help texts ("", "_fluid", "_energy"). */
    public String langSuffix() {
        return switch (this) {
            case FLUID -> "_fluid";
            case ENERGY -> "_energy";
            default -> "";
        };
    }
}
