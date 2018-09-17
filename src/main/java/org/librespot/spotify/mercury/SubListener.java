package org.librespot.spotify.mercury;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public interface SubListener {
    void event(@NotNull MercuryClient.Response resp);
}
