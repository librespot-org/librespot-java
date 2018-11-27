package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.BytesArrayList;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import static xyz.gianlu.librespot.player.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
class AudioFileFetch implements AudioFile {
    private static final Logger LOGGER = Logger.getLogger(AudioFileFetch.class);
    private final CacheManager.Handler cache;
    private final ByteArrayOutputStream headersId = new ByteArrayOutputStream();
    private final BytesArrayList headersData = new BytesArrayList();
    private int size = -1;
    private int chunks = -1;

    AudioFileFetch(@Nullable CacheManager.Handler cache) {
        this.cache = cache;
    }

    @Override
    public void writeChunk(byte[] chunk, int chunkIndex, boolean cached) {
        if (chunkIndex != 0)
            throw new IllegalStateException("chunkIndex not zero: " + chunkIndex);
    }

    @Override
    public synchronized void writeHeader(byte id, byte[] bytes, boolean cached) {
        if (!cached && cache != null) {
            headersId.write(id);
            headersData.add(bytes);
        }

        if (id == 0x3) {
            size = ByteBuffer.wrap(bytes).getInt();
            size *= 4;
            chunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE;
            notifyAll();
        }
    }

    @Override
    public void cacheFailedHeader(@NotNull AudioFile file) {
        LOGGER.fatal("Failed loading headers from cache!");
    }

    @Override
    public synchronized void headerEnd(boolean cached) {
        if (!cached && cache != null) {
            headersId.write(CacheManager.BYTE_CREATED_AT);
            headersData.add(BigInteger.valueOf(System.currentTimeMillis() / 1000).toByteArray());

            cache.writeHeaders(headersId.toByteArray(), headersData.toArray(), (short) chunks);
        }
    }

    @Override
    public synchronized void cacheFailedChunk(int index, @NotNull AudioFile file) {
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
        if (size == -1) throw new IllegalStateException("Headers not received yet!");
        return size;
    }

    public int getChunks() {
        if (chunks == -1) throw new IllegalStateException("Headers not received yet!");
        return chunks;
    }
}
