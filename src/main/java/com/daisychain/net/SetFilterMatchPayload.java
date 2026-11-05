package com.daisychain.net;

import net.minecraft.network.FriendlyByteBuf;


/**
 * Client -> server: "make filter entry N of my open Pull side screen match this way", from the entry's detail page.
 *
 * @param containerId the open menu (must still be the player's current menu)
 * @param slot        filter slot 0-53
 * @param mode        a MatchMode id
 * @param value       the tag id for TAG mode, otherwise ignored (max 256 characters)
 */
public record SetFilterMatchPayload(int containerId, byte slot, byte mode, String value) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.containerId);
        buf.writeByte(this.slot);
        buf.writeByte(this.mode);
        buf.writeUtf(this.value, 256);
    }

    public static SetFilterMatchPayload decode(FriendlyByteBuf buf) {
        return new SetFilterMatchPayload(buf.readVarInt(), buf.readByte(), buf.readByte(), buf.readUtf(256));
    }
}
