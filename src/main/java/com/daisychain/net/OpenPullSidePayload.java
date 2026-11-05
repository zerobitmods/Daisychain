package com.daisychain.net;

import com.daisychain.Daisychain;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: "open the settings of this side" (pull or insert side; the server decides), from the Pipe sides screen. */
public record OpenPullSidePayload(BlockPos pos, byte side) implements CustomPacketPayload {
    public static final Type<OpenPullSidePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "open_pull_side"));

    public static final StreamCodec<ByteBuf, OpenPullSidePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenPullSidePayload::pos,
            ByteBufCodecs.BYTE, OpenPullSidePayload::side,
            OpenPullSidePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
