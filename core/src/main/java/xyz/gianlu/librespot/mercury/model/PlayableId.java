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
        if (TrackId.PATTERN.matcher(uri).matches()) {
            return TrackId.fromUri(uri);
        } else if (EpisodeId.PATTERN.matcher(uri).matches()) {
            return EpisodeId.fromUri(uri);
        } else {
            throw new IllegalArgumentException("Unknown uri: " + uri);
        }
    }

    static boolean isSupported(@NotNull String uri) {
        return !uri.startsWith("spotify:local:") && !uri.equals("spotify:delimiter");
    }

    static int removeUnsupported(@NotNull List<Remote3Track> tracks, int updateIndex) {
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Remote3Track track = tracks.get(i);
            if (!isSupported(track.uri)) {
                tracks.remove(i);

                if (updateIndex != -1) {
                    if (updateIndex == i) updateIndex = -1;

                    if (updateIndex > i)
                        updateIndex--;
                }
            }
        }

        return updateIndex;
    }

    @NotNull byte[] getGid();

    @NotNull String hexId();

    @NotNull String toSpotifyUri();
}
