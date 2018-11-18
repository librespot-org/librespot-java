package org.librespot.spotify.spirc;

import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.proto.Spirc;

/**
 * @author Gianlu
 */
public interface FrameListener {
    void frame(@NotNull Spirc.Frame frame);
}
