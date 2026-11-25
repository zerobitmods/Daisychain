package com.daisychain.client;

import com.daisychain.block.PipeKind;
import com.daisychain.config.DaisychainClientConfig;

/**
 * Step 146: the colours of item, fluid and universal pipes, as tints. Step 147: the player's own colours.
 *
 * Their textures store each body pixel as pixel / surface colour (x 255 / headroom) and the middle line on its own
 * layer, so tint = surface colour x headroom draws the default look and any other surface colour the same way. A tint
 * can only darken, so the brightest surface colour per channel is 255 / headroom ({@link #cap}). The numbers below come
 * from the same build as the textures; a rebuild with another look must update them.
 *
 * Read on chunk-build threads: the tints are plain ints in volatile arrays, set from the config on load / change.
 */
public final class PipeTints {
    /** Colour slot (index in DaisychainClientConfig.COLOUR_KINDS) by PipeKind ordinal (item, fluid, energy, universal); -1 = none. */
    private static final int[] SLOT = {0, 1, -1, 2};
    /** Default surface colour (what the screen shows as "Default") per slot. */
    public static final int[] DEFAULT_SURFACE = {0x4E5358, 0x294B7C, 0x1B1D24};
    /** Default line colour per slot. */
    public static final int[] DEFAULT_LINE = {0xDEB870, 0x6ECCF0, 0xB092E2};
    /** Brightest body pixel / surface per slot (tint = surface x headroom). */
    private static final float[] HEADROOM = {1.35F, 1.7F, 1.7F};
    private static final float LINE_HEADROOM = 1.04F;
    /** Default tints, exactly as the build wrote them (rounding included). */
    private static final int[] DEFAULT_BODY_TINT = {0x697077, 0x4680D3, 0x2E313D};
    private static final int[] DEFAULT_LINE_TINT = {0xE7BF74, 0x72D4FA, 0xB798EB};

    private static volatile int[] bodyTint = DEFAULT_BODY_TINT.clone();
    private static volatile int[] lineTint = DEFAULT_LINE_TINT.clone();

    private PipeTints() {
    }

    /** The colour slot of a pipe kind, or -1 (energy has its own tier colours). */
    public static int slot(PipeKind kind) {
        int k = kind.ordinal();
        return k < SLOT.length ? SLOT[k] : -1;
    }

    /** Brightest value per channel a surface (body) or line colour can have in this slot. */
    public static int cap(int slot, boolean line) {
        return (int) Math.floor(255 / (line ? LINE_HEADROOM : HEADROOM[slot]));
    }

    /** The colour as the game can draw it: each channel at most {@link #cap}. */
    public static int clamp(int slot, boolean line, int rgb) {
        int c = cap(slot, line);
        return Math.min(rgb >> 16 & 0xFF, c) << 16 | Math.min(rgb >> 8 & 0xFF, c) << 8 | Math.min(rgb & 0xFF, c);
    }

    /** Tint for a surface / line colour (-1 = the default). */
    public static int tintFor(int slot, boolean line, int rgb) {
        if (rgb < 0) {
            return line ? DEFAULT_LINE_TINT[slot] : DEFAULT_BODY_TINT[slot];
        }
        float m = line ? LINE_HEADROOM : HEADROOM[slot];
        int r = Math.min(255, Math.round((rgb >> 16 & 0xFF) * m));
        int g = Math.min(255, Math.round((rgb >> 8 & 0xFF) * m));
        int b = Math.min(255, Math.round((rgb & 0xFF) * m));
        return r << 16 | g << 8 | b;
    }

    /** The body tint of a slot as set now. */
    public static int body(int slot) {
        return bodyTint[slot];
    }

    /** The line tint of a slot as set now. */
    public static int line(int slot) {
        return lineTint[slot];
    }

    /**
     * Step 155: the tint of a slot's pull and insert rings. Their texture is plain white while the line's is
     * 255 / LINE_HEADROOM, so the line tint scaled down by the same headroom draws them exactly as bright as the line.
     */
    public static int ring(int slot) {
        int t = lineTint[slot];
        return Math.round((t >> 16 & 0xFF) / LINE_HEADROOM) << 16 | Math.round((t >> 8 & 0xFF) / LINE_HEADROOM) << 8
                | Math.round((t & 0xFF) / LINE_HEADROOM);
    }

    /** Re-reads the config. Returns true if any tint changed (then the world's pipes need redrawing). */
    public static boolean refresh() {
        int n = DEFAULT_BODY_TINT.length;
        int[] body = new int[n];
        int[] line = new int[n];
        for (int i = 0; i < n; i++) {
            body[i] = tintFor(i, false, DaisychainClientConfig.pipeColour(i, false));
            line[i] = tintFor(i, true, DaisychainClientConfig.pipeColour(i, true));
        }
        boolean changed = !java.util.Arrays.equals(body, bodyTint) || !java.util.Arrays.equals(line, lineTint);
        bodyTint = body;
        lineTint = line;
        return changed;
    }
}
