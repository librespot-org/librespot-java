package xyz.gianlu.librespot.player.decrypt;

import java.io.IOException;

/**
 * @author Gianlu
 */
public interface AudioDecrypt {

    void decryptChunk(int chunkIndex, byte[] in, byte[] out) throws IOException;
}
