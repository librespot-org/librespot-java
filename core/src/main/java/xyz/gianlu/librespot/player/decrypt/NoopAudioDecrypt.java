package xyz.gianlu.librespot.player.decrypt;

import org.apache.log4j.Logger;

/**
 * @author Gianlu
 */
public final class NoopAudioDecrypt implements AudioDecrypt {
    private final static Logger LOGGER = Logger.getLogger(NoopAudioDecrypt.class);

    @Override
    public void decryptChunk(int chunkIndex, byte[] in, byte[] out) {
        int length = in.length;
        if (in.length != out.length) {
            length = Math.min(in.length, out.length);
            LOGGER.warn(String.format("Buffers have different lengths! {index: %d, in: %d, out: %d}", chunkIndex, in.length, out.length));
        }

        System.arraycopy(in, 0, out, 0, length);
    }
}
