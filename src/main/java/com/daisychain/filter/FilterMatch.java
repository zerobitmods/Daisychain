package com.daisychain.filter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Shown on the copies in a Pull side screen's filter slots, so the client knows how each entry matches.
 * Only ever on those display copies (which can't be picked up), never on real items.
 *
 * @param mode  a MatchMode id
 * @param value the tag id for TAG mode, the mod id for MOD mode, otherwise ""
 */
public record FilterMatch(byte mode, String value) {
    public static final Codec<FilterMatch> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BYTE.fieldOf("mode").forGetter(FilterMatch::mode),
            Codec.STRING.fieldOf("value").forGetter(FilterMatch::value)
    ).apply(instance, FilterMatch::new));

    public static final StreamCodec<ByteBuf, FilterMatch> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, FilterMatch::mode,
            ByteBufCodecs.stringUtf8(256), FilterMatch::value,
            FilterMatch::new);

    public MatchMode matchMode() {
        MatchMode m = MatchMode.byId(mode);
        return m == null ? MatchMode.ITEM : m;
    }
}
