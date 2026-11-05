package com.daisychain.block;

import net.minecraft.util.StringRepresentable;

/** When a pull side is allowed to work. */
public enum RedstoneMode implements StringRepresentable {
    ALWAYS("always"),
    ON_WITH_SIGNAL("on_with_signal"),
    OFF_WITH_SIGNAL("off_with_signal"),
    /** One transfer each time the signal turns on. */
    PULSE("pulse");

    private static final RedstoneMode[] VALUES = values();
    private final String name;

    RedstoneMode(String name) {
        this.name = name;
    }

    public static RedstoneMode byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : ALWAYS;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
