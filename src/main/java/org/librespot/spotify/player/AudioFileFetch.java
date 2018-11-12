package org.librespot.spotify.player;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

/**
 * @author Gianlu
 */
class AudioFileFetch implements AudioFile {
    private int size = -1;

    AudioFileFetch() {
    }

    @Override
    public void writeChunk(byte[] chunk, int chunkIndex) {
        if (chunkIndex != 0)
            throw new IllegalStateException("chunkIndex not zero: " + chunkIndex);
    }

    @Override
    public synchronized void header(byte id, byte[] bytes) {
        if (id == 0x3) {
            size = ByteBuffer.wrap(bytes).getInt();
            size *= 4;
            notifyAll();
        }
    }

    @Override
    public void cacheFailed(int index, @NotNull AudioFile file) {
        // Never called
    }

    synchronized void waitChunk() {
        if (size != -1) return;

        try {
            wait();
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

    public int getSize() {
        if (size == -1) throw new IllegalStateException("Check not received yet!");
        return size;
    }
}
