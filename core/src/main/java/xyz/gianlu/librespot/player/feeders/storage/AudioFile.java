package xyz.gianlu.librespot.player.feeders.storage;

import xyz.gianlu.librespot.player.GeneralWritableStream;

import java.io.Closeable;
import java.io.IOException;

/**
 * @author Gianlu
 */
public interface AudioFile extends Closeable, GeneralWritableStream {
    void writeChunk(byte[] chunk, int chunkIndex, boolean cached) throws IOException;

    void writeHeader(byte id, byte[] bytes, boolean cached) throws IOException;

    void streamError(int chunkIndex, short code);
}
