package com.daisychain.filter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Shown on the copies in a Pull side screen's filter slots when the entry has its own Batch / Keep in source /
 * Fill destination to, so the client can show them. Only ever on those display copies, never on real items.
 * Each value is ItemFilter.UNSET (-1) when the entry uses the side's value.
 */
public record FilterNumbers(int batch, int keep, int fill) {
    public static final Codec<FilterNumbers> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("batch").forGetter(FilterNumbers::batch),
            Codec.INT.fieldOf("keep").forGetter(FilterNumbers::keep),
            Codec.INT.fieldOf("fill").forGetter(FilterNumbers::fill)
    ).apply(instance, FilterNumbers::new));


    /** Value by ItemFilter.BATCH / KEEP / FILL. */
    public int get(int which) {
        return switch (which) {
            case ItemFilter.BATCH -> batch;
            case ItemFilter.KEEP -> keep;
            default -> fill;
        };
    }
}
