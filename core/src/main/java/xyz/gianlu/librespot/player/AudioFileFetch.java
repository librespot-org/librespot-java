package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cache.CacheManager;
import xyz.gianlu.librespot.common.Utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.SQLException;

import static xyz.gianlu.librespot.player.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public class AudioFileFetch implements AudioFile {
    public static final byte HEADER_SIZE = 0x3;
    public static final byte HEADER_TIMESTAMP = (byte) 0b11111111;
    private static final Logger LOGGER = Logger.getLogger(AudioFileFetch.class);
    private final CacheManager.Handler cache;
    private int size = -1;
    private int chunks = -1;
    private volatile boolean closed = false;

    AudioFileFetch(@Nullable CacheManager.Handler cache) {
        this.cache = cache;
    }

    @Override
    public void writeChunk(byte[] chunk, int chunkIndex, boolean cached) {
        if (chunkIndex != 0)
            throw new IllegalStateException("chunkIndex not zero: " + chunkIndex);
    }

    @Override
    public synchronized void writeHeader(byte id, byte[] bytes, boolean cached) throws IOException {
        if (closed) return;

        if (!cached && cache != null) {
            try {
                cache.setHeader(id, bytes);
            } catch (SQLException ex) {
                if (id == HEADER_SIZE) throw new IOException(ex);
                else LOGGER.warn(String.format("Failed writing header to cache! {id: %s}", Utils.byteToHex(id)));
            }
        }

        if (id == HEADER_SIZE) {
            size = ByteBuffer.wrap(bytes).getInt();
            size *= 4;
            chunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE;
            notifyAll();
        }
    }

    @Override
    public void streamError(short code) {
        LOGGER.fatal(String.format("Stream error, code: %d", code));
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
