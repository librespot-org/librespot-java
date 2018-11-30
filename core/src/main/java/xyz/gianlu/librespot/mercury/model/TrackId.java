package xyz.gianlu.librespot.mercury.model;

import io.seruco.encoding.base62.Base62;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Spirc;

import java.math.BigInteger;
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
    public static TrackId fromUri(@NotNull String uri) {
        Matcher matcher = PATTERN.matcher(uri);
        if (matcher.find()) {
            String id = matcher.group(1);
            return new TrackId(Utils.bytesToHex(BASE62.decode(id.getBytes())));
        } else {
            throw new IllegalArgumentException("Not a Spotify track ID: " + uri);
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
    public @NotNull String toMercuryUri() {
        return "hm://metadata/4/track/" + hexId;
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return "spotify:track:" + new String(BASE62.encode(new BigInteger(hexId, 16).toByteArray()));
    }
}
