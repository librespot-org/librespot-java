package xyz.gianlu.librespot.audio.decrypt;

/**
 * @author Gianlu
 */
public final class NoopAudioDecrypt implements AudioDecrypt {
    @Override
    public void decryptChunk(int chunkIndex, byte[] buffer) {
    }

    @Override
    public int decryptTimeMs() {
        return 0;
    }
}
