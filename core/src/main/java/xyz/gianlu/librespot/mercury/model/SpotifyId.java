package xyz.gianlu.librespot.mercury.model;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @author Gianlu
 */
public interface SpotifyId {
    String STATIC_FROM_URI = "fromUri";
    String STATIC_FROM_BASE62 = "fromBase62";
    String STATIC_FROM_HEX = "fromHex";

    @NotNull
    static <I extends SpotifyId> I fromBase62(Class<I> clazz, String base62) throws SpotifyIdParsingException {
        return callReflection(clazz, STATIC_FROM_BASE62, base62);
    }

    @NotNull
    static <I extends SpotifyId> I fromHex(Class<I> clazz, String hex) throws SpotifyIdParsingException {
        return callReflection(clazz, STATIC_FROM_HEX, hex);
    }

    @NotNull
    static <I extends SpotifyId> I fromUri(Class<I> clazz, String uri) throws SpotifyIdParsingException {
        return callReflection(clazz, STATIC_FROM_URI, uri);
    }

    @SuppressWarnings("unchecked")
    @NotNull
    static <I extends SpotifyId> I callReflection(@NotNull Class<I> clazz, @NotNull String name, @NotNull String arg) throws SpotifyIdParsingException {
        try {
            Method method = clazz.getDeclaredMethod(name, String.class);
            return (I) method.invoke(null, arg);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException ex) {
            throw new SpotifyIdParsingException(ex);
        }
    }

    @NotNull String toSpotifyUri();

    class SpotifyIdParsingException extends Exception {
        SpotifyIdParsingException(Throwable cause) {
            super(cause);
        }
    }
}
