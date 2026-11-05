package com.daisychain.net;

import net.minecraft.network.FriendlyByteBuf;

import net.minecraft.core.BlockPos;

/** Client -> server: "open the settings of this side" (pull or insert side; the server decides), from the Pipe sides screen. */
public record OpenPullSidePayload(BlockPos pos, byte side) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeByte(this.side);
    }

    public static OpenPullSidePayload decode(FriendlyByteBuf buf) {
        return new OpenPullSidePayload(buf.readBlockPos(), buf.readByte());
    }
}
