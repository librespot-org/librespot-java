/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.audio;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;

import static xyz.gianlu.librespot.audio.storage.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public abstract class AbsChunkedInputStream extends InputStream implements HaltListener {
    private static final int PRELOAD_AHEAD = 3;
    private static final int PRELOAD_CHUNK_RETRIES = 2;
    private static final int MAX_CHUNK_TRIES = 128;
    private final Object waitLock = new Object();
    private final int[] retries;
    private final boolean retryOnChunkError;
    private volatile int waitForChunk = -1;
    private volatile ChunkException chunkException = null;
    private int pos = 0;
    private int mark = 0;
    private volatile boolean closed = false;
    private int decodedLength = 0;

    protected AbsChunkedInputStream(boolean retryOnChunkError) {
        this.retries = new int[chunks()];
        this.retryOnChunkError = retryOnChunkError;
    }

    public final boolean isClosed() {
        return closed;
    }

    protected abstract byte[][] buffer();

    public abstract int size();

    @Override
    public void close() {
        closed = true;

        synchronized (waitLock) {
            waitLock.notifyAll();
        }
    }

    @Override
    public final synchronized int available() {
        return size() - pos;
    }

    @Override
    public final boolean markSupported() {
        return true;
    }

    @Override
    public final synchronized void mark(int readAheadLimit) {
        mark = pos;
    }

    @Override
    public final synchronized void reset() {
        pos = mark;
    }

    public final synchronized int pos() {
        return pos;
    }

    public final synchronized void seek(int where) throws IOException {
        if (where < 0) throw new IllegalArgumentException();
        if (closed) throw new IOException("Stream is closed!");
        pos = where;

        checkAvailability(pos / CHUNK_SIZE, false, false);
    }

    @Override
    public final synchronized long skip(long n) throws IOException {
        if (n < 0) throw new IllegalArgumentException();
        if (closed) throw new IOException("Stream is closed!");

        long k = size() - pos;
        if (n < k) k = n;
        pos += k;

        int chunk = pos / CHUNK_SIZE;
        checkAvailability(chunk, false, false);

        return k;
    }

    protected abstract boolean[] requestedChunks();

    protected abstract boolean[] availableChunks();

    protected abstract int chunks();

    /**
     * This mustn't take long!
     */
    protected abstract void requestChunkFromStream(int index);

    /**
     * Should we retry fetching this chunk? MUST be called only for chunks that are needed immediately ({@code wait = true})!
     *
     * @param chunk The chunk index
     * @return Whether we should retry.
     */
    private boolean shouldRetry(int chunk) {
        if (retries[chunk] < 1) return true;
        if (retries[chunk] > MAX_CHUNK_TRIES) return false;
        return !retryOnChunkError;
    }

    /**
     * Chunk if {@param chunk} is available or wait until it becomes, also handles the retry mechanism.
     *
     * @param chunk  The chunk index
     * @param wait   Whether we should wait for {@param chunk} to be available
     * @param halted Whether we have already notified that the retrieving of this chunk is halted
     * @throws IOException If we fail to retrieve this chunk and no more retries are available
     */
    private void checkAvailability(int chunk, boolean wait, boolean halted) throws IOException {
        if (halted && !wait) throw new IllegalArgumentException();

        if (!requestedChunks()[chunk]) {
            requestChunkFromStream(chunk);
            requestedChunks()[chunk] = true;
        }

        for (int i = chunk + 1; i <= Math.min(chunks() - 1, chunk + PRELOAD_AHEAD); i++) {
            if (!requestedChunks()[i] && retries[i] < PRELOAD_CHUNK_RETRIES) {
                requestChunkFromStream(i);
                requestedChunks()[i] = true;
            }
        }

        if (wait) {
            if (availableChunks()[chunk]) return;

            boolean retry = false;
            synchronized (waitLock) {
                if (!halted) streamReadHalted(chunk, System.currentTimeMillis());

                try {
                    chunkException = null;
                    waitForChunk = chunk;
                    waitLock.wait();

                    if (closed) return;

                    if (chunkException != null) {
                        if (shouldRetry(chunk)) retry = true;
                        else throw chunkException;
                    }
                } catch (InterruptedException ex) {
                    throw new IOException(ex);
                }

                if (!retry) streamReadResumed(chunk, System.currentTimeMillis());
            }

            if (retry) {
                try {
                    Thread.sleep((long) (Math.log10(retries[chunk]) * 1000));
                } catch (InterruptedException ignored) {
                }

                checkAvailability(chunk, true, true); // We must exit the synchronized block!
            }
        }
    }

    @Override
    public final synchronized int read(@NotNull byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("Stream is closed!");

        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException(String.format("off: %d, len: %d, buffer: %d", off, len, buffer().length));
        } else if (len == 0) {
            return 0;
        }

        if (pos >= size())
            return -1;

        int i = 0;
        while (true) {
            int chunk = pos / CHUNK_SIZE;
            int chunkOff = pos % CHUNK_SIZE;

            checkAvailability(chunk, true, false);

            int copy = Math.min(buffer()[chunk].length - chunkOff, len - i);
            System.arraycopy(buffer()[chunk], chunkOff, b, off + i, copy);
            i += copy;
            pos += copy;

            if (i == len || pos >= size())
                return i;
        }
    }

    @Override
    public final synchronized int read() throws IOException {
        if (closed) throw new IOException("Stream is closed!");

        if (pos >= size())
            return -1;

        int chunk = pos / CHUNK_SIZE;
        checkAvailability(chunk, true, false);

        return buffer()[chunk][pos++ % CHUNK_SIZE] & 0xff;
    }

    public final void notifyChunkAvailable(int index) {
        availableChunks()[index] = true;
        decodedLength += buffer()[index].length;

        synchronized (waitLock) {
            if (index == waitForChunk && !closed) {
                waitForChunk = -1;
                waitLock.notifyAll();
            }
        }
    }

    public final void notifyChunkError(int index, @NotNull ChunkException ex) {
        availableChunks()[index] = false;
        requestedChunks()[index] = false;
        retries[index] += 1;

        synchronized (waitLock) {
            if (index == waitForChunk && !closed) {
                chunkException = ex;
                waitForChunk = -1;
                waitLock.notifyAll();
            }
        }
    }

    public int decodedLength() {
        return decodedLength;
    }

    public static class ChunkException extends IOException {
        public ChunkException(@NotNull Throwable cause) {
            super(cause);
        }

        protected ChunkException() {
        }

        private ChunkException(@NotNull String message) {
            super(message);
        }

        @NotNull
        public static ChunkException fromStreamError(short streamError) {
            return new ChunkException("Failed due to stream error, code: " + streamError);
        }
    }
}
