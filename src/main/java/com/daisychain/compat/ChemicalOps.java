package com.daisychain.compat;

/**
 * Step 172: what the chemical transfer (network.ChemicalTransfer) needs from a chemical handler and its stacks, written
 * without naming Mekanism. H is the handler type and S the stack type; only compat/mekanism implements this, so the
 * real types stay inside that class. Every method may throw if Mekanism changed; ChemicalTransfer catches that and
 * switches gas off.
 */
public interface ChemicalOps<H, S> {
    /** Number of tanks in the handler. */
    int tanks(H handler);

    /** What one tank holds (an empty stack if nothing). Read only: never modified. */
    S inTank(H handler, int tank);

    boolean isEmpty(S stack);

    long amount(S stack);

    /** The chemical itself (for "same chemical" checks and per-chemical counting). */
    Object chemical(S stack);

    /** A copy of the stack with another amount. */
    S withAmount(S stack, long amount);

    /** Takes up to want's amount of want's chemical out of the handler (simulate = only asks). Returns what came out. */
    S extract(H handler, S want, boolean simulate);

    /** Puts the stack into the handler (simulate = only asks). Returns how much it took. */
    long insert(H handler, S stack, boolean simulate);

    /** Step 178: true if the chemical (from {@link #chemical}) is in the tag (from Chemicals.tagKey). No allocation. */
    boolean hasTag(Object chemical, Object tag);

    /** Step 178: the namespace of the chemical's registry name (its mod). No allocation. */
    String modId(Object chemical);
}
