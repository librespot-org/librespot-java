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
import xyz.gianlu.librespot.common.Base62;
import xyz.gianlu.librespot.common.Utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public final class ArtistId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:artist:(.{22})");
    private static final Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();
    private final String hexId;

    private ArtistId(@NotNull String hex) {
        this.hexId = hex.toLowerCase();
    }

    @NotNull
    public static ArtistId fromUri(@NotNull String uri) {
        Matcher matcher = PATTERN.matcher(uri);
        if (matcher.find()) {
            String id = matcher.group(1);
            return new ArtistId(Utils.bytesToHex(BASE62.decode(id.getBytes(), 16)));
        } else {
            throw new IllegalArgumentException("Not a Spotify artist ID: " + uri);
        }
    }

    @NotNull
    public static ArtistId fromBase62(@NotNull String base62) {
        return new ArtistId(Utils.bytesToHex(BASE62.decode(base62.getBytes(), 16)));
    }

    @NotNull
    public static ArtistId fromHex(@NotNull String hex) {
        return new ArtistId(hex);
    }

    public @NotNull String toMercuryUri() {
        return "hm://metadata/4/artist/" + hexId;
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return "spotify:artist:" + new String(BASE62.encode(Utils.hexToBytes(hexId)));
    }

    public @NotNull String hexId() {
        return hexId;
    }
}
