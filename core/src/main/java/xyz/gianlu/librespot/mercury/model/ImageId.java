package xyz.gianlu.librespot.mercury.model;

import com.spotify.connectstate.model.Player;
import com.spotify.metadata.proto.Metadata;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public final class ImageId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:image:(.{40})");
    private final String hexId;

    private ImageId(@NotNull String hex) {
        this.hexId = hex.toLowerCase();
    }

    @NotNull
    public static ImageId fromUri(@NotNull String uri) {
        Matcher matcher = PATTERN.matcher(uri);
        if (matcher.find()) return new ImageId(matcher.group(1));
        else throw new IllegalArgumentException("Not a Spotify image ID: " + uri);
    }

    @NotNull
    public static ImageId fromHex(@NotNull String hex) {
        return new ImageId(hex);
    }

    public static void putAsMetadata(@NotNull Player.ProvidedTrack.Builder builder, @NotNull Metadata.ImageGroup group) {
        for (Metadata.Image image : group.getImageList()) {
            String key;
            switch (image.getSize()) {
                case DEFAULT:
                    key = "image_url";
                    break;
                case SMALL:
                    key = "image_small_url";
                    break;
                case LARGE:
                    key = "image_large_url";
                    break;
                case XLARGE:
                    key = "image_xlarge_url";
                    break;
                default:
                    continue;
            }

            builder.putMetadata(key, ImageId.fromHex(Utils.bytesToHex(image.getFileId())).toSpotifyUri());
        }
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return "spotify:image:" + hexId;
    }
}
