package org.librespot.spotify.player;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.librespot.spotify.player.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
class ChunksBuffer {
    private final int size;
    private final byte[][] buffer;
    private final boolean[] available;
    private final AudioDecrypt audioDecrypt;
    private final AtomicInteger waitForChunk = new AtomicInteger(-1);
    private InternalStream internalStream;

    ChunksBuffer(int size, int chunks, byte[] key) {
        this.size = size;
        this.buffer = new byte[chunks][CHUNK_SIZE];
        this.available = new boolean[chunks];
        this.audioDecrypt = new AudioDecrypt(key);
    }

    void writeChunk(@NotNull byte[] chunk, int chunkIndex) throws IOException {
        if (chunk.length != buffer[chunkIndex].length)
            throw new IllegalArgumentException(String.format("Buffer size mismatch, required: %d, received: %d, index: %d", buffer[chunkIndex].length, chunk.length, chunkIndex));

        audioDecrypt.decryptBlock(chunkIndex, chunk, buffer[chunkIndex]);
        available[chunkIndex] = true;

        if (chunkIndex == waitForChunk.get()) {
            synchronized (waitForChunk) {
                waitForChunk.notifyAll();
            }
        }
    }

    private void waitFor(int chunkIndex) throws IOException {
        synchronized (waitForChunk) {
            try {
                waitForChunk.set(chunkIndex);
                waitForChunk.wait();
            } catch (InterruptedException ex) {
                throw new IOException(ex);
            }
        }
    }

    @NotNull
    InputStream stream() {
        if (internalStream == null) internalStream = new InternalStream();
        return internalStream;
    }

    private class InternalStream extends InputStream {
        private int pos = 0;
        private int mark;

        private InternalStream() {
        }

        @Override
        public synchronized int available() {
            return size - pos;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public synchronized void mark(int readAheadLimit) {
            mark = pos;
        }

        @Override
        public synchronized void reset() {
            pos = mark;
        }

        @Override
        public synchronized long skip(long n) {
            long k = size - pos;
            if (n < k) k = n < 0 ? 0 : n;
            pos += k;
            return k;
        }

        @Override
        public int read(@NotNull byte[] b, int off, int len) throws IOException {
            return super.read(b, off, len);
        }

        @Override
        public synchronized int read() throws IOException {
            int chunk = pos / CHUNK_SIZE;
            // System.out.println("CHUNK: " + chunk);
            if (!available[chunk])
                waitFor(chunk);

            int i = pos - CHUNK_SIZE * chunk;
            // System.out.println("I: " + i);

            int b = buffer[chunk][i];
            pos++;
            return b;
        }
    }
}
