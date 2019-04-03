package xyz.gianlu.librespot.player.decrypt;

/**
 * @author Gianlu
 */
public final class NoopAudioDecrypt implements AudioDecrypt {
    @Override
    public void decryptChunk(int chunkIndex, byte[] in, byte[] out) {
        if (in.length != out.length)
            throw new IllegalArgumentException(String.format("Buffers have different lengths! {in: %d, out: %d}", in.length, out.length));

        System.arraycopy(in, 0, out, 0, in.length);
    }
}
