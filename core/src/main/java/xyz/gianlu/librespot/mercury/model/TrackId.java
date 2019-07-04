package xyz.gianlu.librespot.mercury.model;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Base62;
import xyz.gianlu.librespot.common.Utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public final class TrackId implements SpotifyId, PlayableId {
    static final Pattern PATTERN = Pattern.compile("spotify:track:(.{22})");
    private static final Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();
    private final String hexId;

    private TrackId(@NotNull String hex) {
        this.hexId = hex.toLowerCase();
    }

    @NotNull
    public static TrackId fromUri(@NotNull String uri) {
        Matcher matcher = PATTERN.matcher(uri);
        if (matcher.find()) {
            String id = matcher.group(1);
            return new TrackId(Utils.bytesToHex(BASE62.decode(id.getBytes(), 16)));
        } else {
            throw new IllegalArgumentException("Not a Spotify track ID: " + uri);
        }
    }

    @NotNull
    public static TrackId fromBase62(@NotNull String base62) {
        return new TrackId(Utils.bytesToHex(BASE62.decode(base62.getBytes(), 16)));
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
        return "spotify:track:" + new String(BASE62.encode(Utils.hexToBytes(hexId), 22));
    }

    @Override
    public @NotNull String hexId() {
        return hexId;
    }

    @Override
    @NotNull
    public byte[] getGid() {
        return Utils.hexToBytes(hexId);
    }

    @Override
    public String toString() {
        return "TrackId{" + toSpotifyUri() + '}';
    }
}
