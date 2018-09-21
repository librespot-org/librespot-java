package org.librespot.spotify.player;

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
            size = ((bytes[0] << 24) + (bytes[1] << 16) + (bytes[2] << 8) + bytes[3]);
            size *= 4;
            notifyAll();
        }
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
