package xyz.gianlu.librespot.mercury.model;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public final class UnsupportedId implements PlayableId {
    private final String uri;

    UnsupportedId(@NotNull String uri) {
        this.uri = uri;
    }

    @Override
    public @NotNull byte[] getGid() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull String hexId() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull String toSpotifyUri() {
        return uri;
    }

    @NotNull
    @Override
    public String toString() {
        return "UnsupportedId{" + toSpotifyUri() + '}';
    }
}
