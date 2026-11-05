package com.daisychain.net;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Server -> client, steps 48e/48f: outline these pipe sides for the Settings Copier (shown while held). Only sent to the
 * player using the copier. positions[i] is BlockPos.asLong, sides[i] a Direction 3D data value (0-5).
 */
public record PasteHighlightPayload(byte kind, long[] positions, byte[] sides) {
    /** Gold: the sides the next (confirming) click will paste onto, for the 3 s confirm window. */
    public static final byte PREVIEW = 0;
    /** Green: a new paste; replaces the previous paste/undo outlines. */
    public static final byte PASTED = 1;
    /** Green: another side of the same streak; added to the current paste's outlines. */
    public static final byte PASTED_STREAK = 2;
    /** Blue: put back by an undo; replaces the previous paste/undo outlines. */
    public static final byte UNDONE = 3;
    /** White: the side the copier's settings came from; replaces the previous one. */
    public static final byte COPIED = 4;
    /** Removes every outline (the copier was cleared). */
    public static final byte CLEAR = 5;

    /** Most sides one message may carry; the server never sends more than its paste limit allows. */
    public static final int MAX_SIDES = 100_000;


    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(this.kind());
        buf.writeVarInt(this.positions().length);
        for (int i = 0; i < this.positions().length; i++) {
            buf.writeLong(this.positions()[i]);
            buf.writeByte(this.sides()[i]);
        }
    }

    public static PasteHighlightPayload decode(FriendlyByteBuf buf) {
        byte kind = buf.readByte();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_SIDES) {
            throw new DecoderException("Too many highlighted sides: " + count);
        }
        long[] positions = new long[count];
        byte[] sides = new byte[count];
        for (int i = 0; i < count; i++) {
            positions[i] = buf.readLong();
            sides[i] = buf.readByte();
        }
        return new PasteHighlightPayload(kind, positions, sides);
    }

}
