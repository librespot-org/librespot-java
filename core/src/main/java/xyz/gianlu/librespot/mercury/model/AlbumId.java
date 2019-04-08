package xyz.gianlu.librespot.mercury.model;

import io.seruco.encoding.base62.Base62;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public final class AlbumId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:album:(.{22})");
    private static final Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();
    private final String hexId;

    private AlbumId(@NotNull String hex) {
        this.hexId = hex.toLowerCase();
    }

    @NotNull
    public static AlbumId fromUri(@NotNull String uri) {
        Matcher matcher = PATTERN.matcher(uri);
        if (matcher.find()) {
            String id = matcher.group(1);
            return new AlbumId(Utils.bytesToHex(BASE62.decode(id.getBytes()), true));
        } else {
            throw new IllegalArgumentException("Not a Spotify album ID: " + uri);
        }
    }

    @NotNull
    public static AlbumId fromBase62(@NotNull String base62) {
        return new AlbumId(Utils.bytesToHex(BASE62.decode(base62.getBytes()), true));
    }

    @NotNull
    public static AlbumId fromHex(@NotNull String hex) {
        return new AlbumId(hex);
    }

    @Override
    public @NotNull String toMercuryUri() {
        return "hm://metadata/4/album/" + hexId;
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return "spotify:album:" + new String(BASE62.encode(Utils.hexToBytes(hexId)));
    }
}
