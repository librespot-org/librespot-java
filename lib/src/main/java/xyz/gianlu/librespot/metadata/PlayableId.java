package xyz.gianlu.librespot.metadata;

import com.google.protobuf.ByteString;
import com.spotify.connectstate.Player;
import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.spotify.context.ContextTrackOuterClass.ContextTrack;

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

    static int indexOfTrack(@NotNull List<ContextTrack> tracks, @NotNull PlayableId id) {
        ByteString gid = ByteString.copyFrom(id.getGid());
        String uri = id.toSpotifyUri();

        for (int i = 0; i < tracks.size(); i++) {
            ContextTrack track = tracks.get(i);
            if ((track.hasUri() && uri.equals(track.getUri())) || (track.hasGid() && gid.equals(track.getGid())))
                return i;
        }

        return -1;
    }

    static boolean cannotPlayAnything(@NotNull List<ContextTrack> tracks) {
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

    int hashCode();

    @NotNull byte[] getGid();

    @NotNull String hexId();

    @NotNull String toSpotifyUri();

    default boolean matches(@NotNull ContextTrack current) {
        String uri = current.getUri();
        if (uri != null && !uri.isEmpty()) return toSpotifyUri().equals(uri);
        else if (current.getGid() != null) return Arrays.equals(current.getGid().toByteArray(), getGid());
        else return false;
    }
}
