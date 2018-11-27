package xyz.gianlu.librespot.mercury.model;

import io.seruco.encoding.base62.Base62;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.Utils;
import xyz.gianlu.librespot.proto.Playlist4Content;
import xyz.gianlu.librespot.proto.Spirc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public class TrackId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:track:(.{22})");
    private static final Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();
    private final String hexId;

    private TrackId(@NotNull String hex) {
        this.hexId = hex;
    }

    @NotNull
    public static TrackId fromItemsList(@NotNull Playlist4Content.Item item) {
        Matcher matcher = PATTERN.matcher(item.getUri());
        if (matcher.find()) {
            String id = matcher.group(1);
            return new TrackId(Utils.bytesToHex(BASE62.decode(id.getBytes())));
        } else {
            throw new IllegalArgumentException("Not a Spotify track ID: " + item.getUri());
        }
    }

    @NotNull
    public static TrackId fromBase62(@NotNull String base62) {
        return new TrackId(Utils.bytesToHex(BASE62.decode(base62.getBytes())));
    }

    @NotNull
    public static TrackId fromTrackRef(@NotNull Spirc.TrackRef ref) {
        return new TrackId(Utils.bytesToHex(ref.getGid().toByteArray()));
    }

    @NotNull
    public static TrackId fromHex(@NotNull String hex) {
        return new TrackId(hex);
    }

    @Override
    public @NotNull String getMercuryUri() {
        return "hm://metadata/4/track/" + hexId;
    }
}
