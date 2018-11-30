package xyz.gianlu.librespot.mercury.model;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public interface SpotifyId {
    @NotNull String toMercuryUri();

    @NotNull String toSpotifyUri();
}
