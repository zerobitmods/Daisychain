package com.daisychain.net;

import com.daisychain.Daisychain;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "give filter entry N of my open Pull side screen its own Batch / Keep / Fill", from the entry's
 * detail page.
 *
 * @param containerId the open menu (must still be the player's current menu)
 * @param slot        filter slot 0-53
 * @param which       ItemFilter.BATCH, KEEP or FILL
 * @param value       the number, or ItemFilter.UNSET (-1) to use the side's value again; range-checked on the server
 */
public record SetFilterNumberPayload(int containerId, byte slot, byte which, int value) implements CustomPacketPayload {
    public static final Type<SetFilterNumberPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "set_filter_number"));

    public static final StreamCodec<ByteBuf, SetFilterNumberPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetFilterNumberPayload::containerId,
            ByteBufCodecs.BYTE, SetFilterNumberPayload::slot,
            ByteBufCodecs.BYTE, SetFilterNumberPayload::which,
            ByteBufCodecs.INT, SetFilterNumberPayload::value,
            SetFilterNumberPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
