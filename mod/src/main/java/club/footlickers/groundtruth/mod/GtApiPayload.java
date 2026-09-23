package club.footlickers.groundtruth.mod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server telling us where its map API is served.
 *
 * <p>The address a player joins on and the address the map is served on are not always the same host,
 * so the plugin states its own public map URL on join rather than every client being configured.
 */
public record GtApiPayload(String url) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GtApiPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("groundtruth", "api"));

    public static final StreamCodec<ByteBuf, GtApiPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, GtApiPayload::url, GtApiPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
