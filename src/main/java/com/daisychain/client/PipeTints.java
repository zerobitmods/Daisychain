package com.daisychain.client;

import com.daisychain.block.PipeKind;

/**
 * Step 146: the colours of item, fluid and universal pipes, as tints.
 *
 * Their textures store each body pixel as pixel / surface colour (x 255 / headroom) and the middle line on its own
 * layer, so tint = surface colour x headroom draws the pipe's look. The numbers below come from the same build as the
 * textures; a rebuild with another look must update them.
 *
 * Read on chunk-build threads: plain ints.
 */
public final class PipeTints {
    /** Colour slot by PipeKind ordinal (item, fluid, energy, universal); -1 = none. */
    private static final int[] SLOT = {0, 1, -1, 2};
    /** Default surface colour per slot. */
    public static final int[] DEFAULT_SURFACE = {0x4E5358, 0x294B7C, 0x1B1D24};
    /** Default line colour per slot. */
    public static final int[] DEFAULT_LINE = {0xDEB870, 0x6ECCF0, 0xB092E2};
    /** Brightest body pixel / surface per slot (tint = surface x headroom). */
    private static final float[] HEADROOM = {1.35F, 1.7F, 1.7F};
    private static final float LINE_HEADROOM = 1.04F;
    /** Default tints, exactly as the build wrote them (rounding included). */
    private static final int[] DEFAULT_BODY_TINT = {0x697077, 0x4680D3, 0x2E313D};
    private static final int[] DEFAULT_LINE_TINT = {0xE7BF74, 0x72D4FA, 0xB798EB};

    private PipeTints() {
    }

    /** The colour slot of a pipe kind, or -1 (energy has its own tier colours). */
    public static int slot(PipeKind kind) {
        int k = kind.ordinal();
        return k < SLOT.length ? SLOT[k] : -1;
    }

    /** The body tint of a slot. */
    public static int body(int slot) {
        return DEFAULT_BODY_TINT[slot];
    }

    /** The line tint of a slot. */
    public static int line(int slot) {
        return DEFAULT_LINE_TINT[slot];
    }
}
