/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.metadata;

import com.google.protobuf.ByteString;
import com.spotify.connectstate.Player;
import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Base62;
import xyz.gianlu.librespot.common.Utils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.spotify.context.ContextTrackOuterClass.ContextTrack;

/**
 * @author Gianlu
 */
public interface PlayableId {
    Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();

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
        ByteString gid;
        if (id instanceof UnsupportedId) gid = null;
        else gid = ByteString.copyFrom(id.getGid());

        String uri = id.toSpotifyUri();
        for (int i = 0; i < tracks.size(); i++) {
            ContextTrack track = tracks.get(i);
            if ((track.hasUri() && uri.equals(track.getUri())) || (track.hasGid() && track.getGid().equals(gid)))
                return i;
        }

        return -1;
    }

    static boolean canPlaySomething(@NotNull List<ContextTrack> tracks) {
        for (ContextTrack track : tracks)
            if (PlayableId.isSupported(track.getUri()) && shouldPlay(track))
                return true;

        return false;
    }

    @Nullable
    static PlayableId from(@NotNull Player.ProvidedTrack track) {
        return track.getUri().isEmpty() ? null : fromUri(track.getUri());
    }

    static boolean isSupported(@NotNull String uri) {
        return !uri.startsWith("spotify:local:") && !Objects.equals(uri, "spotify:delimiter")
                && !Objects.equals(uri, "spotify:meta:delimiter");
    }

    static boolean shouldPlay(@NotNull ContextTrack track) {
        return track.getMetadataOrDefault("force_remove_reasons", "").isEmpty();
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
    static String inferUriPrefix(@NotNull String contextUri) {
        if (contextUri.startsWith("spotify:episode:") || contextUri.startsWith("spotify:show:"))
            return "spotify:episode:";
        else
            return "spotify:track:";
    }

    @NotNull
    String toString();

    int hashCode();

    @NotNull byte[] getGid();

    @NotNull String hexId();

    @NotNull String toSpotifyUri();

    default boolean matches(@NotNull ContextTrack current) {
        if (current.hasUri())
            return toSpotifyUri().equals(current.getUri());
        else if (current.hasGid() && !(this instanceof UnsupportedId))
            return Arrays.equals(current.getGid().toByteArray(), getGid());
        else
            return false;
    }
}
