package com.daisychain.gametest;

import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Steps 132/133: a stand-in claim mod for the game tests. Like FTB Chunks in someone else's claim, it cancels place
 * events at the refused positions (absolute). Registered once, stays for the run; tests remove their positions after.
 */
final class TestClaims {
    private static final Set<BlockPos> REFUSED = ConcurrentHashMap.newKeySet();
    private static boolean listening;

    private TestClaims() {
    }

    static synchronized void refuse(BlockPos absolute) {
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent event) -> {
                if (REFUSED.contains(event.getPos())) {
                    event.setCanceled(true);
                }
            });
        }
        REFUSED.add(absolute.immutable());
    }

    static void allow(BlockPos absolute) {
        REFUSED.remove(absolute);
    }
}
