package com.daisychain.block;

import net.minecraft.util.StringRepresentable;

/**
 * Facades step 3 (step 127): what a pipe's cover means for the world, kept in the block state so collision, light and
 * face hiding work without reading the block entity (the light engine runs off the server thread, where block entities
 * can't be read). Which block the cover is lives in PipeDataBlockEntity.
 */
public enum CoverShape implements StringRepresentable {
    /** No cover: the pipe's own shape, lets light through. */
    NONE("none"),
    /** An opaque full block (stone, planks): full block, blocks light, hides neighbor faces. */
    SOLID("solid"),
    /** A see-through full block (glass, leaves, ice): full block, lets light through. */
    CLEAR("clear");

    private final String name;

    CoverShape(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
