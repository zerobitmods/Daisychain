package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.client.PipeTints;
import com.daisychain.config.DaisychainClientConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Step 147: the pipe colour maths. Picking a pipe's default colour by hand draws exactly the default look, the
 * brightest pickable colour is still one a tint can draw, and the config only takes "#rrggbb" or "".
 * Plain maths, no client needed.
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PipeColourTests {
    private static final String EMPTY = "empty";

    private PipeColourTests() {
    }

    @GameTest(template = EMPTY)
    public static void colourMaths(GameTestHelper helper) {
        for (int slot = 0; slot < DaisychainClientConfig.COLOUR_KINDS.length; slot++) {
            String kind = DaisychainClientConfig.COLOUR_KINDS[slot];
            helper.assertTrue(PipeTints.tintFor(slot, false, PipeTints.DEFAULT_SURFACE[slot]) == PipeTints.tintFor(slot, false, -1),
                    kind + ": the default body picked by hand doesn't draw the default");
            helper.assertTrue(PipeTints.tintFor(slot, true, PipeTints.DEFAULT_LINE[slot]) == PipeTints.tintFor(slot, true, -1),
                    kind + ": the default line picked by hand doesn't draw the default");
            for (boolean line : new boolean[] {false, true}) {
                int brightest = PipeTints.clamp(slot, line, 0xFFFFFF);
                int cap = PipeTints.cap(slot, line);
                helper.assertTrue(brightest == (cap << 16 | cap << 8 | cap), kind + ": white isn't toned down to the cap");
                // One step darker than the brightest still draws darker: the cap never runs into the tint's 255 limit.
                int below = PipeTints.tintFor(slot, line, brightest - 0x010101);
                int top = PipeTints.tintFor(slot, line, brightest);
                helper.assertTrue((below & 0xFF) < (top & 0xFF), kind + ": the brightest colour is past what a tint draws");
            }
        }
        helper.assertTrue(DaisychainClientConfig.parseColour("#4e5358") == 0x4E5358, "#4e5358 not read");
        helper.assertTrue(DaisychainClientConfig.parseColour("4E5358") == 0x4E5358, "4E5358 not read");
        helper.assertTrue(DaisychainClientConfig.parseColour("") == -1, "empty isn't the default");
        helper.assertTrue(DaisychainClientConfig.parseColour("#12345") == -1, "5 digits taken");
        helper.assertTrue(DaisychainClientConfig.parseColour("#zzzzzz") == -1, "non-hex taken");
        helper.assertTrue(DaisychainClientConfig.parseColour(null) == -1, "null taken");
        helper.succeed();
    }
}
