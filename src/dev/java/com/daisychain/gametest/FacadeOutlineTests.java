package com.daisychain.gametest;

import com.daisychain.Daisychain;
import com.daisychain.block.FacadeOutline;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * Step 131: the merged outline of the mass facade preview keeps only the outer edges of each group, each edge once.
 * Counts are in edges of length 1 (a 2-long box edge counts 2).
 */
@GameTestHolder(Daisychain.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FacadeOutlineTests {
    private static final String EMPTY = "empty";

    private FacadeOutlineTests() {
    }

    @GameTest(template = EMPTY)
    public static void outlineShapes(GameTestHelper helper) {
        check(helper, "one block", 12, new int[][] {{0, 0, 0}});
        check(helper, "two in a row", 16, new int[][] {{0, 0, 0}, {1, 0, 0}});
        check(helper, "three in a row", 20, new int[][] {{0, 0, 0}, {1, 0, 0}, {2, 0, 0}});
        check(helper, "two with a gap", 24, new int[][] {{0, 0, 0}, {2, 0, 0}});
        check(helper, "2x2 flat", 20, new int[][] {{0, 0, 0}, {1, 0, 0}, {0, 0, 1}, {1, 0, 1}});
        check(helper, "touching on one edge", 23, new int[][] {{0, 0, 0}, {1, 1, 0}});
        check(helper, "touching on a corner", 24, new int[][] {{0, 0, 0}, {1, 1, 1}});
        int[][] cube = new int[8][];
        int[][] big = new int[27][];
        int n = 0;
        int m = 0;
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    big[m++] = new int[] {x - 1000, y + 60, z + 2000}; // away from 0, negative x too
                    if (x < 2 && y < 2 && z < 2) {
                        cube[n++] = new int[] {x, y, z};
                    }
                }
            }
        }
        check(helper, "2x2x2", 24, cube);
        check(helper, "3x3x3 far away", 36, big);
        helper.succeed();
    }

    private static void check(GameTestHelper helper, String name, int expected, int[][] blocks) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (int[] b : blocks) {
            set.add(BlockPos.asLong(b[0], b[1], b[2]));
        }
        int[] edges = FacadeOutline.edges(set);
        helper.assertTrue(edges.length % FacadeOutline.INTS_PER_EDGE == 0, name + ": broken edge list");
        int count = edges.length / FacadeOutline.INTS_PER_EDGE;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < edges.length; i += FacadeOutline.INTS_PER_EDGE) {
            String key = edges[i] + "," + edges[i + 1] + "," + edges[i + 2] + "," + edges[i + 3];
            helper.assertTrue(seen.add(key), name + ": edge drawn twice: " + key);
        }
        helper.assertTrue(count == expected, name + ": " + count + " edges (should be " + expected + ")");
    }
}
