package com.daisychain.net;

import com.daisychain.Daisychain;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "set this setting on this side to this value". resource = which of the side's resources
 * (items 0, fluids 1, energy 2) a per-resource setting belongs to; shared settings ignore it. Validated on the server
 * (PipeDataBlockEntity.applySetting ignores out-of-range values and resources the pipe doesn't carry). The value is an
 * int for the number settings.
 */
public record UpdatePullSettingPayload(BlockPos pos, byte side, byte setting, int value, byte resource)
        implements CustomPacketPayload {
    public static final Type<UpdatePullSettingPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Daisychain.MOD_ID, "update_pull_setting"));

    public static final StreamCodec<ByteBuf, UpdatePullSettingPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, UpdatePullSettingPayload::pos,
            ByteBufCodecs.BYTE, UpdatePullSettingPayload::side,
            ByteBufCodecs.BYTE, UpdatePullSettingPayload::setting,
            ByteBufCodecs.VAR_INT, UpdatePullSettingPayload::value,
            ByteBufCodecs.BYTE, UpdatePullSettingPayload::resource,
            UpdatePullSettingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
