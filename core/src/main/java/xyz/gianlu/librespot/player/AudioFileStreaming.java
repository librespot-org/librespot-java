package xyz.gianlu.librespot.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.core.Session;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static xyz.gianlu.librespot.player.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class AudioFileStreaming implements AudioFile {
    private static final Logger LOGGER = Logger.getLogger(AudioFileStreaming.class);
    private final CacheManager.Handler cacheHandler;
    private final ByteString fileId;
    private final byte[] key;
    private final Session session;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private int chunks = -1;
    private ChunksBuffer chunksBuffer;

    AudioFileStreaming(@NotNull Session session, @NotNull CacheManager cacheManager, @NotNull Metadata.AudioFile file, byte[] key) {
        this.session = session;
        this.fileId = file.getFileId();
        this.cacheHandler = cacheManager.handler(fileId);
        this.key = key;
    }

    @NotNull
    String getFileIdHex() {
        return Utils.bytesToHex(fileId);
    }

    @NotNull
    InputStream stream() {
        if (chunksBuffer == null) throw new IllegalStateException("Stream not open!");
        return chunksBuffer.stream();
    }

    private void requestChunk(@NotNull ByteString fileId, int index, @NotNull AudioFile file) throws IOException {
        if (cacheHandler != null && cacheHandler.has(index)) cacheHandler.requestChunk(index, file);
        else session.channel().requestChunk(fileId, index, file);
    }

    @NotNull
    private AudioFileFetch requestHeaders() throws IOException {
        AudioFileFetch fetch = new AudioFileFetch(cacheHandler);
        if (cacheHandler != null && cacheHandler.hasHeaders()) cacheHandler.requestHeaders(fetch);
        else requestChunk(fileId, 0, fetch);
        fetch.waitChunk();
        return fetch;
    }

    void open() throws IOException {
        AudioFileFetch fetch = requestHeaders();

        int size = fetch.getSize();
        LOGGER.trace("Track size: " + size);
        chunks = fetch.getChunks();
        LOGGER.trace(String.format("Track has %d chunks.", chunks));

        chunksBuffer = new ChunksBuffer(size, chunks);
        requestChunk(0);
        chunksBuffer.waitFor(0);
    }

    private void requestChunk(int index) throws IOException {
        requestChunk(fileId, index, this);
        chunksBuffer.requested[index] = true; // Just to be sure
    }

    private void requestChunkFromStream(int index) {
        executorService.submit(() -> {
            try {
                requestChunk(index);
            } catch (IOException ex) {
                LOGGER.fatal(String.format("Failed requesting chunk, index: %d", index), ex);
            }
        });
    }

    @Override
    public void writeChunk(byte[] buffer, int chunkIndex, boolean cached) throws IOException {
        if (!cached && cacheHandler != null)
            cacheHandler.write(buffer, chunkIndex);

        chunksBuffer.writeChunk(buffer, chunkIndex);
        LOGGER.trace(String.format("Chunk %d/%d completed, cached: %b, fileId: %s", chunkIndex, chunks, cached, getFileIdHex()));
    }

    @Override
    public void writeHeader(byte id, byte[] bytes, boolean cached) {
    }

    @Override
    public void cacheFailedHeader(@NotNull AudioFile file) {
    }

    @Override
    public void cacheFailedChunk(int index, @NotNull AudioFile file) {
        try {
            session.channel().requestChunk(fileId, index, file);
        } catch (IOException ex) {
            LOGGER.fatal(String.format("Failed requesting chunk, index: %d", index), ex);
        }
    }

    @Override
    public void headerEnd(boolean cached) {
        // Never called
    }

    @Override
    public void streamError(short code) {
        LOGGER.fatal(String.format("Stream error, code: %d", code));
    }

    private class ChunksBuffer {
        private final int size;
        private final byte[][] buffer;
        private final boolean[] available;
        private final boolean[] requested;
        private final AudioDecrypt audioDecrypt;
        private final AtomicInteger waitForChunk = new AtomicInteger(-1);
        private InternalStream internalStream;

        ChunksBuffer(int size, int chunks) {
            this.size = size;
            this.buffer = new byte[chunks][CHUNK_SIZE];
            this.buffer[chunks - 1] = new byte[size % CHUNK_SIZE];
            this.available = new boolean[chunks];
            this.requested = new boolean[chunks];
            this.audioDecrypt = new AudioDecrypt(key);
        }

        void writeChunk(@NotNull byte[] chunk, int chunkIndex) throws IOException {
            if (chunk.length != buffer[chunkIndex].length)
                throw new IllegalArgumentException(String.format("Buffer size mismatch, required: %d, received: %d, index: %d", buffer[chunkIndex].length, chunk.length, chunkIndex));

            audioDecrypt.decryptChunk(chunkIndex, chunk, buffer[chunkIndex]);
            available[chunkIndex] = true;

            if (chunkIndex == waitForChunk.get()) {
                synchronized (waitForChunk) {
                    waitForChunk.set(-1);
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
            private int mark = 0;

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
            public synchronized long skip(long n) throws IOException {
                long k = size - pos;
                if (n < k) k = n < 0 ? 0 : n;
                pos += k;

                int chunk = pos / CHUNK_SIZE;
                checkAvailability(chunk, false);

                return k;
            }

            private void checkAvailability(int chunk, boolean wait) throws IOException {
                if (!requested[chunk]) {
                    requestChunkFromStream(chunk);
                    requested[chunk] = true;
                }

                if (chunk < chunks - 1 && !requested[chunk + 1]) {
                    requestChunkFromStream(chunk + 1);
                    requested[chunk + 1] = true;
                }

                if (wait && !available[chunk])
                    waitFor(chunk);
            }

            @Override
            public int read(@NotNull byte[] b, int off, int len) throws IOException {
                if (off < 0 || len < 0 || len > b.length - off) {
                    throw new IndexOutOfBoundsException(String.format("off: %d, len: %d, buffer: %d", off, len, buffer.length));
                } else if (len == 0) {
                    return 0;
                }

                if (pos >= size)
                    return -1;

                int i = 0;
                while (true) {
                    int chunk = pos / CHUNK_SIZE;
                    int chunkOff = pos % CHUNK_SIZE;

                    checkAvailability(chunk, true);

                    int copy = Math.min(buffer[chunk].length - chunkOff, len - i);
                    System.arraycopy(buffer[chunk], chunkOff, b, off + i, copy);
                    i += copy;
                    pos += copy;

                    if (i == len || pos >= size)
                        return i;
                }
            }

            @Override
            public synchronized int read() throws IOException {
                if (pos >= size)
                    return -1;

                int chunk = pos / CHUNK_SIZE;
                checkAvailability(chunk, true);

                return buffer[chunk][pos++ % CHUNK_SIZE] & 0xff;
            }
        }
    }
}
