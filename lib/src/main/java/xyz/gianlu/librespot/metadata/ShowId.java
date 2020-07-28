package xyz.gianlu.librespot.metadata;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Base62;
import xyz.gianlu.librespot.common.Utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public final class ShowId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:show:(.{22})");
    private static final Base62 BASE62 = Base62.createInstanceWithInvertedCharacterSet();
    private final String hexId;

    private ShowId(@NotNull String hex) {
        this.hexId = hex.toLowerCase();
    }

    @NotNull
    public static ShowId fromUri(@NotNull String uri) {
        Matcher matcher = PATTERN.matcher(uri);
        if (matcher.find()) {
            String id = matcher.group(1);
            return new ShowId(Utils.bytesToHex(BASE62.decode(id.getBytes(), 16)));
        } else {
            throw new IllegalArgumentException("Not a Spotify show ID: " + uri);
        }
    }

    @NotNull
    public static ShowId fromBase62(@NotNull String base62) {
        return new ShowId(Utils.bytesToHex(BASE62.decode(base62.getBytes(), 16)));
    }

    @NotNull
    public static ShowId fromHex(@NotNull String hex) {
        return new ShowId(hex);
    }

    public @NotNull String toMercuryUri() {
        return "hm://metadata/4/show/" + hexId;
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return "spotify:show:" + new String(BASE62.encode(Utils.hexToBytes(hexId)));
    }

    public @NotNull String hexId() {
        return hexId;
    }
}
