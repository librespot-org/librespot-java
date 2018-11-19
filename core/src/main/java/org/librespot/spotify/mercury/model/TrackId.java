package org.librespot.spotify.mercury.model;

import io.seruco.encoding.base62.Base62;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.Utils;
import org.librespot.spotify.proto.Playlist4Content;
import org.librespot.spotify.proto.Spirc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public class TrackId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:track:(.{22})");
    private static final Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();
    private final String hexId;

    public TrackId(@NotNull Playlist4Content.Item item) {
        Matcher matcher = PATTERN.matcher(item.getUri());
        if (matcher.find()) {
            String id = matcher.group(1);
            hexId = Utils.bytesToHex(BASE62.decode(id.getBytes()));
        } else {
            throw new IllegalArgumentException("Not a Spotify track ID: " + item.getUri());
        }
    }

    public TrackId(@NotNull String id) {
        hexId = Utils.bytesToHex(BASE62.decode(id.getBytes()));
    }

    public TrackId(Spirc.TrackRef ref) {
        hexId = Utils.bytesToHex(ref.getGid().toByteArray());
    }

    @Override
    public @NotNull String getMercuryUri() {
        return "hm://metadata/4/track/" + hexId;
    }
}
