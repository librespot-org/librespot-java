package xyz.gianlu.librespot.player;

import java.io.IOException;

/**
 * @author Gianlu
 */
public interface GeneralWritableStream {
    void writeChunk(byte[] buffer, int chunkIndex, boolean cached) throws IOException;
}
