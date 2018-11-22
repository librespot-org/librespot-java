package org.librespot.spotify.mercury;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.mercury.model.PlaylistId;
import org.librespot.spotify.mercury.model.TrackId;
import org.librespot.spotify.proto.Mercury;
import org.librespot.spotify.proto.Metadata;
import org.librespot.spotify.proto.Playlist4Changes;

/**
 * @author Gianlu
 */
public final class MercuryRequests {
    private static final ProtobufMercuryRequest.Processor<Playlist4Changes.SelectedListContent> SELECTED_LIST_CONTENT_PROCESSOR = response -> Playlist4Changes.SelectedListContent.parseFrom(response.payload[0]);
    private static final ProtobufMercuryRequest.Processor<Metadata.Track> METADATA_4_TRACK_PROCESSOR = response -> Metadata.Track.parseFrom(response.payload[0]);
    private static final ProtobufMercuryRequest.Processor<Mercury.MercuryMultiGetReply> MULTI_GET_REPLY_PROCESSOR = response -> Mercury.MercuryMultiGetReply.parseFrom(response.payload[0]);

    private MercuryRequests() {
    }

    @NotNull
    public static ProtobufMercuryRequest<Playlist4Changes.SelectedListContent> getRootPlaylists(@NotNull String username) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(String.format("hm://playlist/user/%s/rootlist", username)),
                SELECTED_LIST_CONTENT_PROCESSOR);
    }

    @NotNull
    public static ProtobufMercuryRequest<Playlist4Changes.SelectedListContent> getPlaylist(@NotNull PlaylistId id) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(id.getMercuryUri()), SELECTED_LIST_CONTENT_PROCESSOR);
    }

    @NotNull
    public static ProtobufMercuryRequest<Metadata.Track> getTrack(@NotNull TrackId id) {
        return new ProtobufMercuryRequest<>(RawMercuryRequest.get(id.getMercuryUri()), METADATA_4_TRACK_PROCESSOR);
    }

    @NotNull
    public static ProtobufMercuryRequest<Mercury.MercuryMultiGetReply> multiGet(@NotNull String uri, Mercury.MercuryRequest... subs) {
        RawMercuryRequest.Builder request = RawMercuryRequest.newBuilder()
                .setContentType("vnd.spotify/mercury-mget-request")
                .setMethod("GET")
                .setUri(uri);

        Mercury.MercuryMultiGetRequest.Builder multi = Mercury.MercuryMultiGetRequest.newBuilder();
        for (Mercury.MercuryRequest sub : subs)
            multi.addRequest(sub);

        request.addProtobufPayload(multi.build());
        return new ProtobufMercuryRequest<>(request.build(), MULTI_GET_REPLY_PROCESSOR);
    }
}
