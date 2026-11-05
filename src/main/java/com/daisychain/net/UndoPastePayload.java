package com.daisychain.net;

import com.daisychain.Daisychain;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server, steps 48f/48h: the "Undo last paste" (redo = false) or "Redo last undo" (redo = true) key was
 * pressed while holding the Settings Copier.
 */
public record UndoPastePayload(boolean redo) implements CustomPacketPayload {
    public static final UndoPastePayload UNDO = new UndoPastePayload(false);
    public static final UndoPastePayload REDO = new UndoPastePayload(true);

    public static final Type<UndoPastePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "undo_paste"));

    public static final StreamCodec<ByteBuf, UndoPastePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, UndoPastePayload::redo,
            UndoPastePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
