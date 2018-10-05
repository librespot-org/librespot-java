package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.Utils;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.proto.Metadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.librespot.spotify.player.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class AudioFileStreaming implements AudioFile {
    private static final Logger LOGGER = Logger.getLogger(AudioFileStreaming.class);
    private final ByteString fileId;
    private final byte[] key;
    private final Session session;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private int chunks = -1;
    private ChunksBuffer chunksBuffer;

    public AudioFileStreaming(@NotNull Session session, @NotNull Metadata.AudioFile file, byte[] key) {
        this.session = session;
        this.fileId = file.getFileId();
        this.key = key;
    }

    @NotNull
    public String getFileIdHex() {
        return Utils.bytesToHex(fileId);
    }

    @NotNull
    public InputStream stream() {
        if (chunksBuffer == null) throw new IllegalStateException("Stream not open!");
        return chunksBuffer.stream();
    }

    public void open() throws IOException {
        AudioFileFetch fetch = new AudioFileFetch();
        session.channel().requestChunk(fileId, 0, fetch);

        fetch.waitChunk();

        int size = fetch.getSize();
        LOGGER.trace("Track size: " + size);
        chunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE;
        LOGGER.trace(String.format("Track has %d chunks.", chunks));

        chunksBuffer = new ChunksBuffer(size, chunks);
        requestChunk(0);
    }

    private void requestChunk(int index) throws IOException {
        session.channel().requestChunk(fileId, index, this);
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
    public void writeChunk(byte[] buffer, int chunkIndex) throws IOException {
        chunksBuffer.writeChunk(buffer, chunkIndex);
        LOGGER.trace(String.format("Chunk %d/%d completed.", chunkIndex, chunks));
    }

    @Override
    public void header(byte id, byte[] bytes) {
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
            public synchronized long skip(long n) {
                long k = size - pos;
                if (n < k) k = n < 0 ? 0 : n;
                pos += k;
                return k;
            }

            private void checkAvailability(int chunk) throws IOException {
                if (!requested[chunk]) {
                    requestChunkFromStream(chunk);
                    requested[chunk] = true;
                }

                if (chunk < chunks - 1 && !requested[chunk + 1]) {
                    requestChunkFromStream(chunk + 1);
                    requested[chunk + 1] = true;
                }

                if (!available[chunk])
                    waitFor(chunk);
            }

            @Override
            public int read(@NotNull byte[] b, int off, int len) throws IOException {
                if (off < 0 || len < 0 || len > b.length - off) {
                    throw new IndexOutOfBoundsException();
                } else if (len == 0) {
                    return 0;
                }

                if (pos >= size)
                    return -1;

                int i = 0;
                while (true) {
                    int chunk = pos / CHUNK_SIZE;
                    int chunkOff = pos % CHUNK_SIZE;

                    checkAvailability(chunk);

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
                checkAvailability(chunk);

                return buffer[chunk][pos++ % CHUNK_SIZE] & 0xff;
            }
        }
    }
}
