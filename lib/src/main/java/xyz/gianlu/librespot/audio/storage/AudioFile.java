package xyz.gianlu.librespot.audio.storage;

import xyz.gianlu.librespot.audio.GeneralWritableStream;

import java.io.Closeable;
import java.io.IOException;

/**
 * @author Gianlu
 */
public interface AudioFile extends Closeable, GeneralWritableStream {
    void writeChunk(byte[] chunk, int chunkIndex, boolean cached) throws IOException;

    void writeHeader(int id, byte[] bytes, boolean cached) throws IOException;

    void streamError(int chunkIndex, short code);
}
