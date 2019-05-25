package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.tracks.TracksProvider;

/**
 * @author Gianlu
 */
public interface SpotifyContext<P extends PlayableId> {
    @NotNull
    static SpotifyContext<?> from(@NotNull String context) {
        String[] split = context.split(":");
        if (split.length < 3)
            throw new IllegalArgumentException(context);

        if (!split[0].equals("spotify"))
            throw new IllegalArgumentException(context);

        return parseBase(split, 1);
    }

    @NotNull
    static SpotifyContext<?> parseBase(String[] split, int i) {
        switch (split[i]) {
            case "user":
                return parseUser(split, i + 1);
            case "internal":
                return parseInternal(split, i + 1);
            default:
                return parseType(split, i);
        }
    }

    @NotNull
    static SpotifyContext<?> parseUser(String[] split, int i) {
        switch (split[i + 1]) {
            case "collection":
                return new CollectionContext();
            default:
                return parseType(split, i + 1);
        }
    }

    @NotNull
    static SpotifyContext<?> parseInternal(String[] split, int i) {
        switch (split[i]) {
            case "recs":
                return parseBase(split, i + 1);
            case "local-files":
                throw new UnsupportedOperationException();
            default:
                throw new IllegalArgumentException(split[i]);
        }
    }

    @NotNull
    static SpotifyContext<?> parseType(String[] split, int i) {
        switch (split[i]) {
            case "playlist":
                return new PlaylistContext();
            case "dailymix":
                return new DailyMixContext();
            case "station":
                return new StationContext();
            case "show":
                return new ShowContext();
            case "episode":
                return new EpisodeContext();
            case "artist":
                return new ArtistContext();
            case "album":
                return new AlbumContext();
            case "genre":
                return new GenreContext();
            default:
                throw new IllegalArgumentException(split[i]);
        }
    }

    @NotNull
    P createId(@NotNull Spirc.TrackRef ref);

    @NotNull
    TracksProvider initProvider(@NotNull Session session, @NotNull Spirc.State.Builder state, @NotNull Player.Configuration conf);
}
