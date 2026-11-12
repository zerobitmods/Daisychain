package com.daisychain.net;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client, step 116c: the busiest pull sides found by /daisychain lag, outlined in red for SECONDS. Only sent to
 * the operator who ran it. Carries the measured dimension, so the outlines show only there: an operator who is
 * somewhere else when the report arrives sees them once they're back (while the time lasts). positions[i] is
 * BlockPos.asLong, sides[i] a Direction 3D data value (0-5).
 */
public record LagHighlightPayload(ResourceLocation dimension, long[] positions, byte[] sides) {
    /** How long the outlines stay, counted from when the report arrives. */
    public static final int SECONDS = 30;
    /** Most sides one message may carry (the server sends at most 15). */
    public static final int MAX_SIDES = 1024;

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(this.dimension());
        int count = Math.min(this.positions().length, this.sides().length);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeLong(this.positions()[i]);
            buf.writeByte(this.sides()[i]);
        }
    }

    public static LagHighlightPayload decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_SIDES) {
            throw new DecoderException("Too many lag outlines: " + count);
        }
        long[] positions = new long[count];
        byte[] sides = new byte[count];
        for (int i = 0; i < count; i++) {
            positions[i] = buf.readLong();
            sides[i] = buf.readByte();
        }
        return new LagHighlightPayload(dimension, positions, sides);
    }
}
