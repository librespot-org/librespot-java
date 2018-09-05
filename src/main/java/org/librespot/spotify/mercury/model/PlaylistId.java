package org.librespot.spotify.mercury.model;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.proto.Playlist4Content;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Gianlu
 */
public class PlaylistId implements SpotifyId {
    private static final Pattern PATTERN = Pattern.compile("spotify:user:(.*):playlist:(.{22})");
    private final String username;
    private final String playlistId;

    public PlaylistId(@NotNull Playlist4Content.Item item) {
        Matcher matcher = PATTERN.matcher(item.getUri());
        if (matcher.find()) {
            username = matcher.group(1);
            playlistId = matcher.group(2);
        } else {
            throw new IllegalArgumentException("Not a Spotify playlist ID: " + item.getUri());
        }
    }

    @Override
    public @NotNull String getMercuryUri() {
        return String.format("hm://playlist/user/%s/playlist/%s", username, playlistId);
    }
}
