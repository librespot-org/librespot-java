package org.librespot.spotify.mercury;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.mercury.model.PlaylistId;
import org.librespot.spotify.mercury.model.TrackId;
import org.librespot.spotify.proto.Metadata;
import org.librespot.spotify.proto.Playlist4Changes;

/**
 * @author Gianlu
 */
public final class MercuryRequests {
    private static final byte[][] EMPTY_PAYLOAD = new byte[0][];
    private static final GeneralMercuryRequest.Processor<Playlist4Changes.SelectedListContent> SELECTED_LIST_CONTENT_PROCESSOR = response -> Playlist4Changes.SelectedListContent.parseFrom(response.payload[0]);
    private static final GeneralMercuryRequest.Processor<Metadata.Track> METADATA_4_TRACK_PROCESSOR = response -> Metadata.Track.parseFrom(response.payload[0]);

    private MercuryRequests() {
    }

    @NotNull
    public static GeneralMercuryRequest<Playlist4Changes.SelectedListContent> getRootPlaylists(@NotNull String username) {
        return new GeneralMercuryRequest<>(String.format("hm://playlist/user/%s/rootlist", username),
                MercuryClient.Method.GET, EMPTY_PAYLOAD, SELECTED_LIST_CONTENT_PROCESSOR);
    }

    @NotNull
    public static GeneralMercuryRequest<Playlist4Changes.SelectedListContent> getPlaylist(@NotNull PlaylistId id) {
        return new GeneralMercuryRequest<>(id.getMercuryUri(), MercuryClient.Method.GET, EMPTY_PAYLOAD, SELECTED_LIST_CONTENT_PROCESSOR);
    }

    @NotNull
    public static GeneralMercuryRequest<Metadata.Track> getTrack(@NotNull TrackId id) {
        return new GeneralMercuryRequest<>(id.getMercuryUri(), MercuryClient.Method.GET, EMPTY_PAYLOAD, METADATA_4_TRACK_PROCESSOR);
    }
}
