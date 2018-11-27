package xyz.gianlu.librespot.mercury.model;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.proto.Playlist4Content;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public class PlaylistId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:user:(.*):playlist:(.{22})");
    public final String username;
    public final String playlistId;

    private PlaylistId(@NotNull String username, @NotNull String playlistId) {
        this.username = username;
        this.playlistId = playlistId;
    }

    @NotNull
    public static PlaylistId fromItemsList(@NotNull Playlist4Content.Item item) {
        Matcher matcher = PATTERN.matcher(item.getUri());
        if (matcher.find()) {
            return new PlaylistId(matcher.group(1), matcher.group(2));
        } else {
            throw new IllegalArgumentException("Not a Spotify playlist ID: " + item.getUri());
        }
    }

    @Override
    public @NotNull String getMercuryUri() {
        return String.format("hm://playlist/user/%s/playlist/%s", username, playlistId);
    }
}
