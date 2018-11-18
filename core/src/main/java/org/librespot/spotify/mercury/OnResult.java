package org.librespot.spotify.mercury;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public interface OnResult<M> {
    void result(@NotNull M result);

    void failed(@NotNull Exception ex);
}
