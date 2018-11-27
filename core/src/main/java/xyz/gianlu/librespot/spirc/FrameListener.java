package xyz.gianlu.librespot.spirc;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.proto.Spirc;

/**
 * @author Gianlu
 */
public interface FrameListener {
    void frame(@NotNull Spirc.Frame frame);
}
