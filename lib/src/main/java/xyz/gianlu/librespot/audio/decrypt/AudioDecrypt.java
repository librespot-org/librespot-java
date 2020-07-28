package xyz.gianlu.librespot.audio.decrypt;

import java.io.IOException;

/**
 * @author Gianlu
 */
public interface AudioDecrypt {
    void decryptChunk(int chunkIndex, byte[] buffer) throws IOException;

    int decryptTimeMs();
}
