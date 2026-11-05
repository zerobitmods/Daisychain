package com.daisychain.compat.ultimine;

import dev.ftb.mods.ftbultimine.api.rightclick.RegisterRightClickHandlerEvent;

/**
 * Step 98 (zerobit): optional FTB Ultimine add-on. Only loaded when Ultimine is installed and new enough to have its
 * right-click API (Daisychain checks both before touching this class), so Ultimine is never required.
 */
public final class UltimineCompat {
    /** Ultimine's mod id. */
    public static final String MOD_ID = "ftbultimine";
    /** A class that only exists in Ultimine versions with the right-click API (1.21.1: 2101.1.2+). */
    public static final String API_CLASS = "dev.ftb.mods.ftbultimine.api.rightclick.RegisterRightClickHandlerEvent";

    private UltimineCompat() {
    }

    public static void register() {
        RegisterRightClickHandlerEvent.REGISTER.register(dispatcher -> dispatcher.registerHandler(UltimineSwapHandler.INSTANCE));
    }
}
