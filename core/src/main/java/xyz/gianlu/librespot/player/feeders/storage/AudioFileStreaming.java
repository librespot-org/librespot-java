package xyz.gianlu.librespot.player.feeders.storage;

import com.google.protobuf.ByteString;
import com.spotify.metadata.proto.Metadata;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.AbsChunkedInputStream;
import xyz.gianlu.librespot.player.GeneralAudioStream;
import xyz.gianlu.librespot.player.HaltListener;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.codecs.SuperAudioFormat;
import xyz.gianlu.librespot.player.decrypt.AesAudioDecrypt;
import xyz.gianlu.librespot.player.decrypt.AudioDecrypt;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static xyz.gianlu.librespot.player.feeders.storage.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class AudioFileStreaming implements AudioFile, GeneralAudioStream {
    private static final Logger LOGGER = Logger.getLogger(AudioFileStreaming.class);
    private final CacheManager.Handler cacheHandler;
    private final Metadata.AudioFile file;
    private final byte[] key;
    private final Session session;
    private final HaltListener haltListener;
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory(r -> "storage-async-" + r.hashCode()));
    private int chunks = -1;
    private ChunksBuffer chunksBuffer;

    public AudioFileStreaming(@NotNull Session session, @NotNull Metadata.AudioFile file, byte[] key, @Nullable HaltListener haltListener) throws IOException {
        this.session = session;
        this.haltListener = haltListener;
        this.cacheHandler = session.cache().forFileId(Utils.bytesToHex(file.getFileId()));
        this.file = file;
        this.key = key;
    }

    @Override
    public @NotNull SuperAudioFormat codec() {
        return SuperAudioFormat.get(file.getFormat());
    }

    @Override
    public @NotNull String describe() {
        return "{fileId: " + Utils.bytesToHex(file.getFileId()) + "}";
    }

    @NotNull
    public InputStream stream() {
        if (chunksBuffer == null) throw new IllegalStateException("Stream not open!");
        return chunksBuffer.stream();
    }

    private void requestChunk(@NotNull ByteString fileId, int index, @NotNull AudioFile file) {
        if (cacheHandler == null || !tryCacheChunk(index)) {
            try {
                session.channel().requestChunk(fileId, index, file);
            } catch (IOException ex) {
                LOGGER.fatal(String.format("Failed requesting chunk from network, index: %d", index), ex);
                chunksBuffer.internalStream.notifyChunkError(index, new AbsChunkedInputStream.ChunkException(ex));
            }
        }
    }

    private boolean tryCacheChunk(int index) {
        try {
            if (!cacheHandler.hasChunk(index)) return false;
            cacheHandler.readChunk(index, this);
            return true;
        } catch (SQLException | IOException ex) {
            LOGGER.fatal(String.format("Failed requesting chunk from cache, index: %d", index), ex);
            return false;
        }
    }

    private boolean tryCacheHeaders(@NotNull AudioFileFetch fetch) throws IOException {
        try {
            List<CacheManager.Header> headers = cacheHandler.getAllHeaders();
            if (headers.isEmpty())
                return false;

            for (CacheManager.Header header : headers)
                fetch.writeHeader(header.id, header.value, true);

            return true;
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @NotNull
    private AudioFileFetch requestHeaders() throws IOException {
        AudioFileFetch fetch = new AudioFileFetch(cacheHandler);
        if (cacheHandler == null || !tryCacheHeaders(fetch))
            requestChunk(file.getFileId(), 0, fetch);

        fetch.waitChunk();
        return fetch;
    }

    public void open() throws IOException {
        AudioFileFetch fetch = requestHeaders();
        int size = fetch.getSize();
        chunks = fetch.getChunks();
        chunksBuffer = new ChunksBuffer(size, chunks);
        // FIXME: Check that we don't need to wait for the first chunk
    }

    private void requestChunk(int index) {
        requestChunk(file.getFileId(), index, this);
        chunksBuffer.requested[index] = true; // Just to be sure
    }

    @Override
    public void writeChunk(byte[] buffer, int chunkIndex, boolean cached) throws IOException {
        if (!cached && cacheHandler != null) {
            try {
                cacheHandler.writeChunk(buffer, chunkIndex);
            } catch (SQLException ex) {
                LOGGER.warn(String.format("Failed writing to cache! {index: %d}", chunkIndex), ex);
            }
        }

        chunksBuffer.writeChunk(buffer, chunkIndex);
        LOGGER.trace(String.format("Chunk %d/%d completed, cached: %b, fileId: %s", chunkIndex, chunks, cached, Utils.bytesToHex(file.getFileId())));
    }

    @Override
    public void writeHeader(byte id, byte[] bytes, boolean cached) {
        // Not interested
    }

    @Override
    public void streamError(int chunkIndex, short code) {
        LOGGER.fatal(String.format("Stream error, index: %d, code: %d", chunkIndex, code));
        chunksBuffer.internalStream.notifyChunkError(chunkIndex, AbsChunkedInputStream.ChunkException.from(code));
    }

    @Override
    public void close() {
        executorService.shutdown();
        if (chunksBuffer != null)
            chunksBuffer.close();
    }

    private class ChunksBuffer implements Closeable {
        private final int size;
        private final byte[][] buffer;
        private final boolean[] available;
        private final boolean[] requested;
        private final AudioDecrypt audioDecrypt;
        private final InternalStream internalStream;

        ChunksBuffer(int size, int chunks) {
            this.size = size;
            this.buffer = new byte[chunks][CHUNK_SIZE];
            this.buffer[chunks - 1] = new byte[size % CHUNK_SIZE];
            this.available = new boolean[chunks];
            this.requested = new boolean[chunks];
            this.audioDecrypt = new AesAudioDecrypt(key);
            this.internalStream = new InternalStream(session.conf());
        }

        void writeChunk(@NotNull byte[] chunk, int chunkIndex) throws IOException {
            if (internalStream.isClosed()) return;

            if (chunk.length != buffer[chunkIndex].length)
                throw new IllegalArgumentException(String.format("Buffer size mismatch, required: %d, received: %d, index: %d", buffer[chunkIndex].length, chunk.length, chunkIndex));

            audioDecrypt.decryptChunk(chunkIndex, chunk, buffer[chunkIndex]);
            internalStream.notifyChunkAvailable(chunkIndex);
        }

        @NotNull
        InputStream stream() {
            return internalStream;
        }

        @Override
        public void close() {
            internalStream.close();
        }

        private class InternalStream extends AbsChunkedInputStream {

            private InternalStream(Player.@NotNull Configuration conf) {
                super(conf);
            }

            @Override
            protected byte[][] buffer() {
                return buffer;
            }

            @Override
            protected int size() {
                return size;
            }

            @Override
            protected boolean[] requestedChunks() {
                return requested;
            }

            @Override
            protected boolean[] availableChunks() {
                return available;
            }

            @Override
            protected int chunks() {
                return chunks;
            }

            @Override
            protected void requestChunkFromStream(int index) {
                executorService.submit(() -> requestChunk(index));
            }

            @Override
            public void streamReadHalted(int chunk, long time) {
                if (haltListener != null) executorService.submit(() -> haltListener.streamReadHalted(chunk, time));
            }

            @Override
            public void streamReadResumed(int chunk, long time) {
                if (haltListener != null) executorService.submit(() -> haltListener.streamReadResumed(chunk, time));
            }
        }
    }
}
