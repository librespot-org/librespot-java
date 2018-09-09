package org.librespot.spotify.mercury.model;

import io.seruco.encoding.base62.Base62;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.Utils;
import org.librespot.spotify.proto.Playlist4Content;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public class TrackId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:track:(.{22})");
    private static final Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();
    public final String id;
    private final String hexId;

    public TrackId(@NotNull Playlist4Content.Item item) {
        Matcher matcher = PATTERN.matcher(item.getUri());
        if (matcher.find()) {
            id = matcher.group(1);
        } else {
            throw new IllegalArgumentException("Not a Spotify track ID: " + item.getUri());
        }

        hexId = Utils.bytesToHex(BASE62.decode(id.getBytes()));
    }

    @Override
    public @NotNull String getMercuryUri() {
        return "hm://metadata/4/track/" + hexId;
    }
}
