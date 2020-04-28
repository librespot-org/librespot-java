package xyz.gianlu.librespot.player.decrypt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @author Gianlu
 */
public final class NoopAudioDecrypt implements AudioDecrypt {
    private final static Logger LOGGER = LogManager.getLogger(NoopAudioDecrypt.class);

    @Override
    public void decryptChunk(int chunkIndex, byte[] in, byte[] out) {
        int length = in.length;
        if (in.length != out.length) {
            length = Math.min(in.length, out.length);
            LOGGER.warn("Buffers have different lengths! {index: {}, in: {}, out: {}}", chunkIndex, in.length, out.length);
        }

        System.arraycopy(in, 0, out, 0, length);
    }
}
