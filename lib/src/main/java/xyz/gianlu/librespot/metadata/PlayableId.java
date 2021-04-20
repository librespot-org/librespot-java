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

import static com.spotify.context.ContextTrackOuterClass.ContextTrack;

/**
 * @author Gianlu
 */
public interface PlayableId {
    Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();

    @NotNull
    static PlayableId fromUri(@NotNull String uri) {
        if (isDelimiter(uri)) return new UnsupportedId(uri);
        else if (isLocal(uri)) return new LocalId(uri);
        else if (TrackId.PATTERN.matcher(uri).matches()) return TrackId.fromUri(uri);
        else if (EpisodeId.PATTERN.matcher(uri).matches()) return EpisodeId.fromUri(uri);
        else throw new IllegalArgumentException("Unknown uri: " + uri);
    }

    static int indexOfTrack(@NotNull List<ContextTrack> tracks, @NotNull PlayableId id) {
        ByteString gid;
        if (id.hasGid()) gid = ByteString.copyFrom(id.getGid());
        else gid = null;

        String uri = id.toSpotifyUri();
        for (int i = 0; i < tracks.size(); i++) {
            ContextTrack track = tracks.get(i);
            if ((track.hasUri() && uri.equals(track.getUri())) || (track.hasGid() && track.getGid().equals(gid)))
                return i;
        }

        return -1;
    }

    @Nullable
    static PlayableId from(@NotNull Player.ProvidedTrack track) {
        return track.getUri().isEmpty() ? null : fromUri(track.getUri());
    }

    static boolean isDelimiter(@NotNull String uri) {
        return uri.equals("spotify:delimiter") || uri.equals("spotify:meta:delimiter");
    }

    static boolean isLocal(@NotNull String uri) {
        return uri.startsWith("spotify:local:");
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

    /**
     * @return Whether this {@link PlayableId} has a GID and hex ID.
     */
    boolean hasGid();

    default @NotNull byte[] getGid() {
        throw new UnsupportedOperationException();
    }

    default @NotNull String hexId() {
        throw new UnsupportedOperationException();
    }

    @NotNull String toSpotifyUri();

    default boolean matches(@NotNull ContextTrack current) {
        if (current.hasUri())
            return toSpotifyUri().equals(current.getUri());
        else if (current.hasGid() && hasGid())
            return Arrays.equals(current.getGid().toByteArray(), getGid());
        else
            return false;
    }
}
