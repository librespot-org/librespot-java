package xyz.gianlu.librespot.player.mixing.output;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;

/**
 * @author devgianlu
 */
public final class StreamOutput implements SinkOutput {
    private final OutputStream stream;
    private final boolean close;

    public StreamOutput(@NotNull OutputStream stream, boolean close) {
        this.stream = stream;
        this.close = close;
    }

    @Override
    public void write(byte[] buffer, int offset, int len) throws IOException {
        stream.write(buffer, offset, len);
    }

    @Override
    public void close() throws IOException {
        if (close) stream.close();
    }
}
