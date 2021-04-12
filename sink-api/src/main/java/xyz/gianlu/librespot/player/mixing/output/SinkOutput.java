package xyz.gianlu.librespot.player.mixing.output;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Range;

import java.io.Closeable;
import java.io.IOException;

/**
 * @author devgianlu
 */
public interface SinkOutput extends Closeable {
    default boolean start(@NotNull OutputAudioFormat format) throws SinkException{
        return false;
    }

    void write(byte[] buffer, int offset, int len) throws IOException;

    default boolean setVolume(@Range(from = 0, to = 1) float volume) {
        return false;
    }

    default void release() {
    }

    default void drain() {
    }

    default void flush() {
    }

    default void stop() {
    }
}
