package com.daisychain.block;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

import java.util.Arrays;

/**
 * Step 131: the merged outline of a set of blocks (the mass facade preview, Brandon's option B). Only the outer edges of
 * each group are kept: an edge between two selected blocks lying side by side in the same face is dropped, as are edges
 * inside the group. Plain reading, no client classes, so the game tests can check it.
 * <p>
 * A block edge is shared by the four blocks around it. Looking at which of those four are selected:
 * one or three → a corner of the shape, kept; two side by side → a flat face, dropped; two diagonal (touching only along
 * that edge) → kept; four → inside, dropped. Each kept edge is written once, by the first selected block of the four.
 */
public final class FacadeOutline {
    /** Each edge is 4 ints: the block corner it starts at (x, y, z) and its axis (0 = x, 1 = y, 2 = z). Length 1. */
    public static final int INTS_PER_EDGE = 4;

    private FacadeOutline() {
    }

    /** The outline of these block positions (BlockPos.asLong), as edges (see INTS_PER_EDGE). */
    public static int[] edges(LongSet selected) {
        int[] out = new int[Math.max(16, selected.size() * 12 * INTS_PER_EDGE)];
        int n = 0;
        int[] p = new int[3];
        int[] q = new int[3];
        LongIterator it = selected.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            p[0] = BlockPos.getX(packed);
            p[1] = BlockPos.getY(packed);
            p[2] = BlockPos.getZ(packed);
            for (int axis = 0; axis < 3; axis++) {
                int u = (axis + 1) % 3;
                int v = (axis + 2) % 3;
                for (int du = 0; du <= 1; du++) {
                    for (int dv = 0; dv <= 1; dv++) {
                        // The four blocks around this edge, in a fixed order: (i, j) = (0,0), (0,1), (1,0), (1,1).
                        int count = 0;
                        int first = -1;
                        boolean s00 = false;
                        boolean s11 = false;
                        for (int i = 0; i <= 1; i++) {
                            for (int j = 0; j <= 1; j++) {
                                q[0] = p[0];
                                q[1] = p[1];
                                q[2] = p[2];
                                q[u] += du - 1 + i;
                                q[v] += dv - 1 + j;
                                if (selected.contains(BlockPos.asLong(q[0], q[1], q[2]))) {
                                    count++;
                                    if (first < 0) {
                                        first = i * 2 + j;
                                    }
                                    if (i == 0 && j == 0) {
                                        s00 = true;
                                    } else if (i == 1 && j == 1) {
                                        s11 = true;
                                    }
                                }
                            }
                        }
                        // This block is (i, j) = (1 - du, 1 - dv); only the first selected one writes the edge.
                        if (first != (1 - du) * 2 + (1 - dv)) {
                            continue;
                        }
                        boolean diagonal = count == 2 && s00 == s11;
                        if (count == 1 || count == 3 || diagonal) {
                            if (n + INTS_PER_EDGE > out.length) {
                                out = Arrays.copyOf(out, out.length * 2);
                            }
                            out[n] = p[0];
                            out[n + 1] = p[1];
                            out[n + 2] = p[2];
                            out[n + u] += du;
                            out[n + v] += dv;
                            out[n + 3] = axis;
                            n += INTS_PER_EDGE;
                        }
                    }
                }
            }
        }
        return Arrays.copyOf(out, n);
    }
}
