package xyz.gianlu.librespot.mercury.model;

import io.seruco.encoding.base62.Base62;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Spirc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public final class TrackId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:track:(.{22})");
    private static final Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();
    private final String hexId;

    private TrackId(@NotNull String hex) {
        if (hex.length() == 32) this.hexId = hex;
        else if (hex.length() == 34 && hex.startsWith("00")) this.hexId = hex.substring(2);
        else throw new IllegalArgumentException("Illegal track id: " + hex);
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
        if (ref.hasGid()) {
            return new TrackId(Utils.bytesToHex(ref.getGid()));
        } else if (ref.hasUri()) {
            return fromUri(ref.getUri());
        } else {
            throw new IllegalArgumentException("Not enough data to extract the track ID!");
        }
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
        return "spotify:track:" + new String(BASE62.encode(Utils.hexToBytes(hexId)));
    }

    public byte[] getGid() {
        return Utils.hexToBytes(hexId);
    }
}
