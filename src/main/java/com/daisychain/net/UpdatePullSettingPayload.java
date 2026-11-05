package com.daisychain.net;

import net.minecraft.network.FriendlyByteBuf;

import net.minecraft.core.BlockPos;

/**
 * Client -> server: "set this setting on this side to this value". resource = which of the side's resources
 * (items 0, fluids 1, energy 2) a per-resource setting belongs to; shared settings ignore it. Validated on the server
 * (PipeDataBlockEntity.applySetting ignores out-of-range values and resources the pipe doesn't carry). The value is an
 * int for the number settings.
 */
public record UpdatePullSettingPayload(BlockPos pos, byte side, byte setting, int value, byte resource)
        {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeByte(this.side);
        buf.writeByte(this.setting);
        buf.writeVarInt(this.value);
        buf.writeByte(this.resource);
    }

    public static UpdatePullSettingPayload decode(FriendlyByteBuf buf) {
        return new UpdatePullSettingPayload(buf.readBlockPos(), buf.readByte(), buf.readByte(), buf.readVarInt(), buf.readByte());
    }
}
