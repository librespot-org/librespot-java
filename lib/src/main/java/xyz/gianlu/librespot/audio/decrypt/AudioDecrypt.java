package xyz.gianlu.librespot.audio.decrypt;

import java.io.IOException;

/**
 * @author Gianlu
 */
public interface AudioDecrypt {
    void decryptChunk(int chunkIndex, byte[] in, byte[] out) throws IOException;

    int decryptTimeMs();
}
