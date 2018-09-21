package org.librespot.spotify.player;

import java.io.IOException;

/**
 * @author Gianlu
 */
public interface AudioFile {
    void writeChunk(byte[] chunk, int chunkIndex) throws IOException;

    void header(byte id, byte[] bytes);
}
