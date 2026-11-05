package com.daisychain.net;

import net.minecraft.network.FriendlyByteBuf;


/**
 * Client -> server: "give filter entry N of my open Pull side screen its own Batch / Keep / Fill", from the entry's
 * detail page.
 *
 * @param containerId the open menu (must still be the player's current menu)
 * @param slot        filter slot 0-53
 * @param which       ItemFilter.BATCH, KEEP or FILL
 * @param value       the number, or ItemFilter.UNSET (-1) to use the side's value again; range-checked on the server
 */
public record SetFilterNumberPayload(int containerId, byte slot, byte which, int value) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.containerId);
        buf.writeByte(this.slot);
        buf.writeByte(this.which);
        buf.writeInt(this.value);
    }

    public static SetFilterNumberPayload decode(FriendlyByteBuf buf) {
        return new SetFilterNumberPayload(buf.readVarInt(), buf.readByte(), buf.readByte(), buf.readInt());
    }
}
