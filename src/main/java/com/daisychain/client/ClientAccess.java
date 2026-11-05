package com.daisychain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * The only place common code calls into client-only code. Only ever called when
 * level.isClientSide() is true, so this class is never loaded on a dedicated server.
 */
public final class ClientAccess {
    private ClientAccess() {
    }

    public static void openPipeSidesScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new PipeSidesScreen(pos));
    }

    public static void showPasteHighlight(byte kind, long[] positions, byte[] sides) {
        PasteHighlights.show(kind, positions, sides);
    }
}
