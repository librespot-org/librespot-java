package xyz.gianlu.librespot.mercury.model;

import com.spotify.connectstate.model.Player;
import org.jetbrains.annotations.NotNull;
import spotify.player.proto.ContextTrackOuterClass.ContextTrack;

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
        return !uri.startsWith("spotify:local:") && !uri.equals("spotify:delimiter");
    }

    static boolean shouldPlay(@NotNull ContextTrack track) {
        String forceRemoveReasons = track.getMetadataOrDefault("force_remove_reasons", null);
        return forceRemoveReasons == null || forceRemoveReasons.isEmpty();
    }

    @NotNull
    static PlayableId from(@NotNull ContextTrack track) {
        return fromUri(track.getUri());
    }

    @NotNull byte[] getGid();

    @NotNull String hexId();

    @NotNull String toSpotifyUri();
}
