package xyz.gianlu.librespot.player.feeders.storage;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.player.feeders.AbsChunkedInputStream;

import java.io.IOException;
import java.nio.ByteBuffer;

import static xyz.gianlu.librespot.player.feeders.storage.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class AudioFileFetch implements AudioFile {
    public static final byte HEADER_SIZE = 0x3;
    public static final byte HEADER_CDN = 0x4;
    private static final Logger LOGGER = Logger.getLogger(AudioFileFetch.class);
    private final CacheManager.Handler cache;
    private int size = -1;
    private int chunks = -1;
    private volatile boolean closed = false;
    private AbsChunkedInputStream.ChunkException exception = null;

    AudioFileFetch(@Nullable CacheManager.Handler cache) {
        this.cache = cache;
    }

    @Override
    public void writeChunk(byte[] chunk, int chunkIndex, boolean cached) {
        if (chunkIndex != 0)
            throw new IllegalStateException("chunkIndex not zero: " + chunkIndex);
    }

    @Override
    public synchronized void writeHeader(int id, byte[] bytes, boolean cached) throws IOException {
        if (closed) return;

        if (!cached && cache != null) {
            try {
                cache.setHeader(id, bytes);
            } catch (IOException ex) {
                if (id == HEADER_SIZE) throw new IOException(ex);
                else LOGGER.warn(String.format("Failed writing header to cache! {id: %s}", Utils.byteToHex((byte) id)));
            }
        }

        if (id == HEADER_SIZE) {
            size = ByteBuffer.wrap(bytes).getInt();
            size *= 4;
            chunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE;

            exception = null;
            notifyAll();
        } else if (id == HEADER_CDN) {
            exception = new StorageNotAvailable(new String(bytes));
            notifyAll();
        }
    }

    @Override
    public synchronized void streamError(int chunkIndex, short code) {
        LOGGER.fatal(String.format("Stream error, index: %d, code: %d", chunkIndex, code));

        exception = AbsChunkedInputStream.ChunkException.fromStreamError(code);
        notifyAll();
    }

    synchronized void waitChunk() throws AbsChunkedInputStream.ChunkException {
        if (size != -1) return;

        try {
            exception = null;
            wait();

            if (exception != null)
                throw exception;
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static class StorageNotAvailable extends AbsChunkedInputStream.ChunkException {
        public final String cdnUrl;

        StorageNotAvailable(@NotNull String cdnUrl) {
            this.cdnUrl = cdnUrl;
        }
    }

    public int getSize() {
        if (size == -1) throw new IllegalStateException("Headers not received yet!");
        return size;
    }

    public int getChunks() {
        if (chunks == -1) throw new IllegalStateException("Headers not received yet!");
        return chunks;
    }

    @Override
    public void close() {
        closed = true;
    }
}
