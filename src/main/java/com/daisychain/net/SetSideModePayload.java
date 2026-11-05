package com.daisychain.net;

import com.daisychain.Daisychain;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server, step 48: "set this pipe side to this mode" from the Pipe sides screen's mode keys. mode is
 * FlowPipeBlock.MODE_NORMAL / MODE_PULL / MODE_DISCONNECTED; the server only applies modes allowed for that side.
 */
public record SetSideModePayload(BlockPos pos, byte side, byte mode) implements CustomPacketPayload {
    public static final Type<SetSideModePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "set_side_mode"));

    public static final StreamCodec<ByteBuf, SetSideModePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetSideModePayload::pos,
            ByteBufCodecs.BYTE, SetSideModePayload::side,
            ByteBufCodecs.BYTE, SetSideModePayload::mode,
            SetSideModePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
