package com.daisychain.net;

import com.daisychain.Daisychain;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "make filter entry N of my open Pull side screen match this way", from the entry's detail page.
 *
 * @param containerId the open menu (must still be the player's current menu)
 * @param slot        filter slot 0-53
 * @param mode        a MatchMode id
 * @param value       the tag id for TAG mode, otherwise ignored (max 256 characters)
 */
public record SetFilterMatchPayload(int containerId, byte slot, byte mode, String value) implements CustomPacketPayload {
    public static final Type<SetFilterMatchPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "set_filter_match"));

    public static final StreamCodec<ByteBuf, SetFilterMatchPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetFilterMatchPayload::containerId,
            ByteBufCodecs.BYTE, SetFilterMatchPayload::slot,
            ByteBufCodecs.BYTE, SetFilterMatchPayload::mode,
            ByteBufCodecs.stringUtf8(256), SetFilterMatchPayload::value,
            SetFilterMatchPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
