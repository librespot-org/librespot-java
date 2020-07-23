package xyz.gianlu.librespot.audio;

import java.io.IOException;

/**
 * @author Gianlu
 */
public interface GeneralWritableStream {
    void writeChunk(byte[] buffer, int chunkIndex, boolean cached) throws IOException;
}
