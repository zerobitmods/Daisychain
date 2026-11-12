package com.daisychain.net;

import com.daisychain.Daisychain;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client, step 116c: the busiest pull sides found by /daisychain lag, outlined in red for SECONDS. Only sent to
 * the operator who ran it. Carries the measured dimension, so the outlines show only there: an operator who is
 * somewhere else when the report arrives sees them once they're back (while the time lasts). positions[i] is
 * BlockPos.asLong, sides[i] a Direction 3D data value (0-5).
 */
public record LagHighlightPayload(ResourceLocation dimension, long[] positions, byte[] sides)
        implements CustomPacketPayload {
    /** How long the outlines stay, counted from when the report arrives. */
    public static final int SECONDS = 30;
    /** Most sides one message may carry (the server sends at most 15). */
    public static final int MAX_SIDES = 1024;

    public static final Type<LagHighlightPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "lag_highlight"));

    public static final StreamCodec<FriendlyByteBuf, LagHighlightPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeResourceLocation(payload.dimension());
                int count = Math.min(payload.positions().length, payload.sides().length);
                buf.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    buf.writeLong(payload.positions()[i]);
                    buf.writeByte(payload.sides()[i]);
                }
            },
            buf -> {
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
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
