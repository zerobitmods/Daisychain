package com.daisychain.block;

import net.minecraft.util.StringRepresentable;

/** Which destination a pull side sends to first. Distance is measured along the pipes. */
public enum Distribution implements StringRepresentable {
    NEAREST("nearest"),
    FARTHEST("farthest"),
    ROUND_ROBIN("round_robin");

    private static final Distribution[] VALUES = values();
    private final String name;

    Distribution(String name) {
        this.name = name;
    }

    public static Distribution byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : NEAREST;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
