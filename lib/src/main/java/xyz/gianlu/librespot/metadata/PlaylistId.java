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

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public final class PlaylistId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:user:(.*):playlist:(.{22})");
    public final String username;
    public final String playlistId;

    private PlaylistId(@NotNull String username, @NotNull String playlistId) {
        this.username = username;
        this.playlistId = playlistId;
    }

    @NotNull
    public static PlaylistId fromUri(@NotNull String uri) {
        Matcher matcher = PATTERN.matcher(uri);
        if (matcher.find()) {
            return new PlaylistId(matcher.group(1), matcher.group(2));
        } else {
            throw new IllegalArgumentException("Not a Spotify playlist ID: " + uri);
        }
    }

    public @NotNull String toMercuryUri(boolean annotate) {
        if (annotate)
            return String.format("hm://playlist-annotate/v1/annotation/user/%s/playlist/%s", username, playlistId);
        else
            return String.format("hm://playlist/user/%s/playlist/%s", username, playlistId);
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return String.format("spotify:user:%s:playlist:%s", username, playlistId);
    }
}
