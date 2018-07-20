package org.librespot.spotify.utils;

import org.jetbrains.annotations.NotNull;

/**
 * @author Gianlu
 */
public interface OnResult<R> {

    void onResult(@NotNull R result);

    void onException(@NotNull Exception ex);
}
