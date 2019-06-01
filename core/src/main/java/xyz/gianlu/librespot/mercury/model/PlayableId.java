package xyz.gianlu.librespot.mercury.model;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.remote.Remote3Track;

import java.util.List;

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

    static boolean hasAtLeastOneSupportedId(@NotNull List<Remote3Track> tracks) {
        for (Remote3Track track : tracks)
            if (track.isSupported())
                return true;

        return false;
    }

    static boolean isSupported(@NotNull String uri) {
        return !uri.startsWith("spotify:local:") && !uri.equals("spotify:delimiter");
    }

    @NotNull byte[] getGid();

    @NotNull String hexId();

    @NotNull String toSpotifyUri();
}
