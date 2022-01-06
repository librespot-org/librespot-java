/*
 * Copyright 2022 devgianlu
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
 * @author devgianlu
 */
public final class PlaylistId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:playlist:(.{22})");
    public final String id;

    private PlaylistId(@NotNull String id) {
        this.id = id;
    }

    @NotNull
    public static PlaylistId fromUri(@NotNull String uri) {
        Matcher matcher = PATTERN.matcher(uri);
        if (matcher.find()) return new PlaylistId(matcher.group(1));
        else throw new IllegalArgumentException("Not a Spotify playlist ID: " + uri);
    }

    @NotNull
    public String id() {
        return id;
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return String.format("spotify:playlist:%s", id);
    }
}
