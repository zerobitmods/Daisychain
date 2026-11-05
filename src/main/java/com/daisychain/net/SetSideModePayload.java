package com.daisychain.net;

import net.minecraft.network.FriendlyByteBuf;

import net.minecraft.core.BlockPos;

/**
 * Client -> server, step 48: "set this pipe side to this mode" from the Pipe sides screen's mode keys. mode is
 * FlowPipeBlock.MODE_NORMAL / MODE_PULL / MODE_DISCONNECTED; the server only applies modes allowed for that side.
 */
public record SetSideModePayload(BlockPos pos, byte side, byte mode) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeByte(this.side);
        buf.writeByte(this.mode);
    }

    public static SetSideModePayload decode(FriendlyByteBuf buf) {
        return new SetSideModePayload(buf.readBlockPos(), buf.readByte(), buf.readByte());
    }
}
