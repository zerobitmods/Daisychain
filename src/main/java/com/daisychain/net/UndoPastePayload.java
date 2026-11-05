package com.daisychain.net;

import net.minecraft.network.FriendlyByteBuf;


/**
 * Client -> server, steps 48f/48h: the "Undo last paste" (redo = false) or "Redo last undo" (redo = true) key was
 * pressed while holding the Settings Copier.
 */
public record UndoPastePayload(boolean redo) {
    public static final UndoPastePayload UNDO = new UndoPastePayload(false);
    public static final UndoPastePayload REDO = new UndoPastePayload(true);

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(this.redo);
    }

    public static UndoPastePayload decode(FriendlyByteBuf buf) {
        return new UndoPastePayload(buf.readBoolean());
    }
}
