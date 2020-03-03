package xyz.gianlu.librespot.player.contexts;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.connectstate.RestrictionsManager;
import xyz.gianlu.librespot.core.Session;

import java.util.Objects;

/**
 * @author Gianlu
 */
public abstract class AbsSpotifyContext {
    public final RestrictionsManager restrictions;
    protected final String context;

    AbsSpotifyContext(@NotNull String context) {
        this.context = context;
        this.restrictions = new RestrictionsManager(this);
    }

    public static boolean isCollection(@NotNull Session session, @NotNull String uri) {
        return Objects.equals(uri, "spotify:user:" + session.username() + ":collection");
    }

    @NotNull
    public static AbsSpotifyContext from(@NotNull String context) {
        if (context.startsWith("spotify:dailymix:") || context.startsWith("spotify:station:"))
            return new GeneralInfiniteContext(context);
        else if (context.startsWith("spotify:search:"))
            return new SearchContext(context, context.substring(15));
        else
            return new GeneralFiniteContext(context);
    }

    @Override
    public String toString() {
        return "AbsSpotifyContext{context='" + context + "'}";
    }

    public abstract boolean isFinite();

    public final @NotNull String uri() {
        return context;
    }

    public static class UnsupportedContextException extends Exception {
        UnsupportedContextException(@NotNull String message) {
            super(message);
        }

        @NotNull
        public static UnsupportedContextException cannotPlayAnything() {
            return new UnsupportedContextException("Nothing from this context can or should be played!");
        }
    }
}
