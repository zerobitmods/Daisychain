package com.daisychain.block;

import net.minecraft.util.StringRepresentable;

/** What one side of a pipe looks like and does. */
public enum SideState implements StringRepresentable {
    /** No arm on this side. */
    NONE("none"),
    /** Arm connected to another pipe or to an inventory (items can be delivered here). */
    CONNECTED("connected"),
    /** Arm connected to an inventory that this pipe pulls from. */
    EXTRACT("extract");

    private final String name;

    SideState(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
