package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.providers.ContentProvider;
import xyz.gianlu.librespot.player.remote.Remote3Frame;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * @author Gianlu
 */
public abstract class AbsSpotifyContext<P extends PlayableId> {
    protected final String context;
    private boolean canRepeatContext = false;
    private boolean canShuffle = false;
    private boolean canRepeatTrack = false;

    public AbsSpotifyContext(@NotNull String context) {
        this.context = context;
    }

    @NotNull
    public static AbsSpotifyContext<?> from(@NotNull String context) throws UnsupportedContextException {
        String[] split = context.split(":");
        if (split.length < 3)
            throw new IllegalArgumentException(context);

        if (!split[0].equals("spotify"))
            throw new IllegalArgumentException(context);

        return parseBase(context, split, 1);
    }

    @NotNull
    private static AbsSpotifyContext<?> parseBase(String original, String[] split, int i) throws UnsupportedContextException {
        switch (split[i]) {
            case "user":
                return parseUser(original, split, i + 1);
            case "internal":
                return parseInternal(original, split, i + 1);
            default:
                return parseType(original, split, i);
        }
    }

    @NotNull
    private static AbsSpotifyContext<?> parseUser(String original, String[] split, int i) {
        switch (split[i + 1]) {
            case "collection":
                return new CollectionContext(original);
            default:
                return parseType(original, split, i + 1);
        }
    }

    @NotNull
    private static AbsSpotifyContext<?> parseInternal(String original, String[] split, int i) throws UnsupportedContextException {
        switch (split[i]) {
            case "recs":
                return parseBase(original, split, i + 1);
            case "local-files":
                throw new UnsupportedContextException(String.join(":", split));
            default:
                throw new IllegalArgumentException(original);
        }
    }

    @NotNull
    private static AbsSpotifyContext<?> parseType(String original, String[] split, int i) {
        switch (split[i]) {
            case "playlist":
                return new PlaylistContext(original);
            case "dailymix":
                return new DailyMixContext(original);
            case "station":
                return new StationContext(original);
            case "show":
                return new ShowContext(original);
            case "episode":
                return new EpisodeContext(original);
            case "artist":
                return new ArtistContext(original);
            case "album":
                return new AlbumContext(original);
            case "genre":
                return new GenreContext(original);
            case "track":
                return new TrackContext(original);
            case "search":
                try {
                    return new SearchContext(original, URLDecoder.decode(split[i + 1], "UTF-8"));
                } catch (UnsupportedEncodingException ex) {
                    throw new RuntimeException(ex);
                }
            default:
                throw new IllegalArgumentException(split[i]);
        }
    }

    @NotNull
    public abstract P createId(@NotNull Spirc.TrackRef ref);

    public abstract P createId(@NotNull String uri);

    public abstract boolean isFinite();

    public final boolean canRepeatContext() {
        return canRepeatContext;
    }

    public final boolean canRepeatTrack() {
        return canRepeatTrack;
    }

    public final boolean canShuffle() {
        return canShuffle;
    }

    public final void updateRestrictions(@NotNull Remote3Frame.Context.Restrictions restrictions) {
        canRepeatContext = restrictions.allowed(Remote3Frame.Context.Restrictions.Type.TOGGLING_REPEAT_CONTEXT);
        canRepeatTrack = restrictions.allowed(Remote3Frame.Context.Restrictions.Type.TOGGLING_REPEAT_TRACK);
        canShuffle = restrictions.allowed(Remote3Frame.Context.Restrictions.Type.TOGGLING_SHUFFLE);
    }

    @Nullable
    public ContentProvider initProvider(@NotNull Session session) {
        if (isFinite()) return null;
        else throw new UnsupportedOperationException(context);
    }

    public final @NotNull String uri() {
        return context;
    }

    public static class UnsupportedContextException extends Exception {
        UnsupportedContextException(@NotNull String message) {
            super(message);
        }

        @NotNull
        public static UnsupportedContextException empty() {
            return new UnsupportedContextException("Empty context not supported!");
        }
    }
}
