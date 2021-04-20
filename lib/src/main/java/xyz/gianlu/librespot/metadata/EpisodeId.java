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
import xyz.gianlu.librespot.common.Utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public final class EpisodeId implements SpotifyId, PlayableId {
    static final Pattern PATTERN = Pattern.compile("spotify:episode:(.{22})");
    private final String hexId;

    private EpisodeId(@NotNull String hex) {
        this.hexId = hex.toLowerCase();
    }

    @NotNull
    public static EpisodeId fromUri(@NotNull String uri) {
        Matcher matcher = PATTERN.matcher(uri);
        if (matcher.find()) {
            String id = matcher.group(1);
            return new EpisodeId(Utils.bytesToHex(BASE62.decode(id.getBytes(), 16)));
        } else {
            throw new IllegalArgumentException("Not a Spotify episode ID: " + uri);
        }
    }

    @NotNull
    public static EpisodeId fromBase62(@NotNull String base62) {
        return new EpisodeId(Utils.bytesToHex(BASE62.decode(base62.getBytes(), 16)));
    }

    @NotNull
    public static EpisodeId fromHex(@NotNull String hex) {
        return new EpisodeId(hex);
    }

    public @NotNull String toMercuryUri() {
        return "hm://metadata/4/episode/" + hexId;
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return "spotify:episode:" + new String(BASE62.encode(Utils.hexToBytes(hexId)));
    }

    @Override
    public @NotNull String hexId() {
        return hexId;
    }

    @Override
    public boolean hasGid() {
        return true;
    }

    @Override
    @NotNull
    public byte[] getGid() {
        return Utils.hexToBytes(hexId);
    }

    @NotNull
    @Override
    public String toString() {
        return "EpisodeId{" + toSpotifyUri() + '}';
    }

    @Override
    public int hashCode() {
        return hexId.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EpisodeId episodeId = (EpisodeId) o;
        return hexId.equals(episodeId.hexId);
    }
}
