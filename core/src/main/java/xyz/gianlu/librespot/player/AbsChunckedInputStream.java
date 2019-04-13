package xyz.gianlu.librespot.player;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static xyz.gianlu.librespot.player.feeders.storage.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public abstract class AbsChunckedInputStream extends InputStream {
    private static final int PRELOAD_AHEAD = 3;
    private final AtomicInteger waitForChunk = new AtomicInteger(-1);
    private final HaltListener haltListener;
    private ChunkException chunkException = null;
    private int pos = 0;
    private int mark = 0;
    private volatile boolean closed = false;

    protected AbsChunckedInputStream(@Nullable HaltListener haltListener) {
        this.haltListener = haltListener;
    }

    public final boolean isClosed() {
        return closed;
    }

    protected abstract byte[][] buffer();

    protected abstract int size();

    @Override
    public final void close() {
        closed = true;
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

    @Override
    public final synchronized long skip(long n) throws IOException {
        if (closed) throw new IOException("Stream is closed!");

        long k = size() - pos;
        if (n < k) k = n < 0 ? 0 : n;
        pos += k;

        int chunk = pos / CHUNK_SIZE;
        checkAvailability(chunk, false);

        return k;
    }

    public void waitFor(int chunkIndex) throws IOException {
        if (isClosed()) return;

        if (availableChunks()[chunkIndex]) return;

        synchronized (waitForChunk) {
            try {
                chunkException = null;

                waitForChunk.set(chunkIndex);
                waitForChunk.wait();

                if (chunkException != null)
                    throw chunkException;
            } catch (InterruptedException ex) {
                throw new IOException(ex);
            }
        }
    }

    protected abstract boolean[] requestedChunks();

    protected abstract boolean[] availableChunks();

    protected abstract int chunks();

    /**
     * This mustn't take long!
     */
    protected abstract void requestChunkFromStream(int index);

    private void checkAvailability(int chunk, boolean wait) throws IOException {
        if (!requestedChunks()[chunk]) {
            requestChunkFromStream(chunk);
            requestedChunks()[chunk] = true;
        }

        for (int i = chunk + 1; i <= Math.min(chunks() - 1, chunk + PRELOAD_AHEAD); i++) {
            if (!requestedChunks()[i]) {
                requestChunkFromStream(i);
                requestedChunks()[i] = true;
            }
        }

        if (availableChunks()[chunk]) return;

        if (wait) {
            if (haltListener != null) haltListener.streamReadHalted(chunk, System.currentTimeMillis());
            waitFor(chunk);
            if (haltListener != null) haltListener.streamReadResumed(chunk, System.currentTimeMillis());
        }
    }

    @Override
    public final int read(@NotNull byte[] b, int off, int len) throws IOException {
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

            checkAvailability(chunk, true);

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
        checkAvailability(chunk, true);

        return buffer()[chunk][pos++ % CHUNK_SIZE] & 0xff;
    }

    public final void notifyChunkAvailable(int index) {
        availableChunks()[index] = true;

        if (index == waitForChunk.get()) {
            synchronized (waitForChunk) {
                waitForChunk.set(-1);
                waitForChunk.notifyAll();
            }
        }
    }

    public final void notifyChunkError(int index, @NotNull ChunkException ex) {
        availableChunks()[index] = false;
        requestedChunks()[index] = false;

        if (index == waitForChunk.get()) {
            synchronized (waitForChunk) {
                chunkException = ex;
                waitForChunk.set(-1);
                waitForChunk.notifyAll();
            }
        }
    }

    public interface HaltListener {
        void streamReadHalted(int chunk, long time);

        void streamReadResumed(int chunk, long time);
    }

    public static class ChunkException extends IOException {
        public ChunkException(@NotNull Throwable cause) {
            super(cause);
        }

        private ChunkException(@NotNull String message) {
            super(message);
        }

        @NotNull
        public static ChunkException from(short streamError) {
            return new ChunkException("Failed due to stream error, code: " + streamError);
        }
    }
}
