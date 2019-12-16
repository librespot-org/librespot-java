package xyz.gianlu.librespot.mercury.model;

import com.spotify.connectstate.model.Player;
import com.spotify.metadata.proto.Metadata;
import org.jetbrains.annotations.NotNull;
import spotify.player.proto.ContextTrackOuterClass.ContextTrack;
import xyz.gianlu.librespot.common.Utils;

import java.util.List;
import java.util.Objects;

/**
 * @author Gianlu
 */
public interface PlayableId {
    @NotNull
    static PlayableId fromUri(@NotNull String uri) {
        if (!isSupported(uri)) return new UnsupportedId(uri);

        if (TrackId.PATTERN.matcher(uri).matches()) {
            return TrackId.fromUri(uri);
        } else if (EpisodeId.PATTERN.matcher(uri).matches()) {
            return EpisodeId.fromUri(uri);
        } else {
            throw new IllegalArgumentException("Unknown uri: " + uri);
        }
    }

    static boolean canPlaySomething(@NotNull List<ContextTrack> tracks) {
        for (ContextTrack track : tracks)
            if (PlayableId.isSupported(track.getUri()) && shouldPlay(track))
                return true;

        return false;
    }

    @NotNull
    static PlayableId from(@NotNull Player.ProvidedTrack track) {
        return fromUri(track.getUri());
    }

    static boolean isSupported(@NotNull String uri) {
        return !uri.startsWith("spotify:local:") && !Objects.equals(uri, "spotify:delimiter")
                && !Objects.equals(uri, "spotify:meta:delimiter");
    }

    static boolean shouldPlay(@NotNull ContextTrack track) {
        String forceRemoveReasons = track.getMetadataOrDefault("force_remove_reasons", null);
        return forceRemoveReasons == null || forceRemoveReasons.isEmpty();
    }

    @NotNull
    static PlayableId from(@NotNull ContextTrack track) {
        return fromUri(track.getUri());
    }

    @NotNull
    static PlayableId from(@NotNull Metadata.Track track) {
        return TrackId.fromHex(Utils.bytesToHex(track.getGid()));
    }

    @NotNull
    static PlayableId from(@NotNull Metadata.Episode episode) {
        return EpisodeId.fromHex(Utils.bytesToHex(episode.getGid()));
    }

    @NotNull
    String toString();

    @NotNull byte[] getGid();

    @NotNull String hexId();

    @NotNull String toSpotifyUri();
}
