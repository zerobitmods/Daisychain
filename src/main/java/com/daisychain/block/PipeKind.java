package com.daisychain.block;

/** What a pipe type carries. Each pipe block has one; pipes of different blocks never connect. */
public enum PipeKind {
    ITEM,
    FLUID,
    ENERGY,
    /**
     * Carries items, fluids and energy at once (step 33), and Mekanism chemicals (step 176); each side has settings per
     * resource.
     */
    UNIVERSAL,
    /**
     * Step 161: carries a redstone signal (on / off), no resource. Pull sides read a signal, insert sides send it out.
     * Never part of an item / fluid / energy network: it has no settings screens, upgrades, filters or pull extractors.
     */
    REDSTONE,
    /**
     * Step 170: Mekanism chemicals (gas, infusion, pigment, slurry), counted in mB. The gas pipe carries them (step 172),
     * and so does the universal pipe (step 176). Filters since step 178.
     */
    GAS;

    /**
     * What a pipe side can carry, as an index into per-resource settings (step 31): items 0, fluids 1, energy 2,
     * chemicals 3 (step 170). Item, fluid, energy and gas pipes carry one resource each; the universal pipe (step 32)
     * carries items, fluids and energy.
     */
    public static final int RESOURCE_COUNT = 4;
    public static final int ITEMS = 0;
    public static final int FLUIDS = 1;
    public static final int ENERGY_RES = 2;
    public static final int CHEMICALS = 3;
    private static final PipeKind[] BY_RESOURCE = {ITEM, FLUID, ENERGY, GAS};

    /** The kind that matches one resource (ITEM, FLUID, ENERGY or GAS): decides its units, limits and help texts. */
    public static PipeKind ofResource(int resource) {
        return BY_RESOURCE[resource];
    }

    /** This pipe's own resource, or -1 if it carries several (universal). */
    public int resource() {
        return switch (this) {
            case ITEM -> ITEMS;
            case FLUID -> FLUIDS;
            case ENERGY -> ENERGY_RES;
            case GAS -> CHEMICALS;
            case UNIVERSAL, REDSTONE -> -1;
        };
    }

    /**
     * True if sides of this pipe carry that resource. Step 161: redstone pipes carry none. Step 170: chemicals on gas
     * pipes; step 176: and on universal pipes (which then carry all four).
     */
    public boolean carries(int resource) {
        if (resource == CHEMICALS) {
            return this == GAS || this == UNIVERSAL;
        }
        return resource >= 0 && resource < RESOURCE_COUNT && this != REDSTONE
                && (this == UNIVERSAL || resource() == resource);
    }

    /** Step 161: true for the redstone pipe, which carries a signal instead of a resource. */
    public boolean isRedstone() {
        return this == REDSTONE;
    }

    /** The first resource this pipe carries (what its settings screen opens on; redstone pipes have none). */
    public int firstResource() {
        return Math.max(0, resource());
    }

    /** Largest fluid amount (mB) any number setting can hold: a million buckets. */
    public static final int MAX_FLUID_AMOUNT = 1_000_000_000;
    /** Largest energy amount (FE) any number setting can hold: the most one energy handler call can carry. */
    public static final int MAX_ENERGY_AMOUNT = Integer.MAX_VALUE;
    private static final int MAX_ITEM_BATCH = 1_000_000;
    private static final int MAX_ITEM_STOCK = 100_000;

    /**
     * Item, fluid and gas pipes have the 54-slot filter (items, fluids or chemicals; chemicals since step 178). Energy
     * is all one kind: no filter.
     */
    public boolean hasFilter() {
        return this == ITEM || this == FLUID || this == GAS;
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
            case FLUID, GAS -> MAX_FLUID_AMOUNT; // chemicals count mB like fluids
            case ENERGY -> MAX_ENERGY_AMOUNT;
            default -> MAX_ITEM_BATCH;
        };
    }

    /** Highest Keep in source / Fill destination to / Max stock value (0 = off). */
    public int maxStock() {
        return switch (this) {
            case FLUID, GAS -> MAX_FLUID_AMOUNT; // chemicals count mB like fluids
            case ENERGY -> MAX_ENERGY_AMOUNT;
            default -> MAX_ITEM_STOCK;
        };
    }

    /** One click on a number button: 1 item, 100 mB, or 1,000 FE. */
    public int smallStep() {
        return switch (this) {
            case FLUID, GAS -> 100;
            case ENERGY -> 1_000;
            default -> 1;
        };
    }

    /** Shift+click on a number button: 64 items, 1000 mB (one bucket), or 100,000 FE. */
    public int bigStep() {
        return switch (this) {
            case FLUID, GAS -> 1_000;
            case ENERGY -> 100_000;
            default -> 64;
        };
    }

    /** Suffix of this kind's own help texts ("", "_fluid", "_energy", "_gas"). */
    public String langSuffix() {
        return switch (this) {
            case FLUID -> "_fluid";
            case GAS -> "_gas"; // the texts themselves come with the gas pipe (step 172)
            case ENERGY -> "_energy";
            default -> "";
        };
    }
}
