package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.librespot.spotify.Utils;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.librespot.spotify.player.ChannelManager.CHUNK_SIZE;


/**
 * @author Gianlu
 */
public class CacheManager {
    private static final Logger LOGGER = Logger.getLogger(CacheManager.class);
    private final File cacheDir;
    private final boolean enabled;
    private final Map<String, Handler> loadedHandlers;
    private final ControlTable controlTable;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public CacheManager(@NotNull CacheConfiguration conf) throws IOException {
        this.enabled = conf.cacheEnabled();
        if (enabled) {
            this.loadedHandlers = new HashMap<>();
            this.cacheDir = conf.cacheDir();
            if (!cacheDir.exists() && !cacheDir.mkdir())
                throw new IllegalStateException("Cannot create cache dir!");

            this.controlTable = new ControlTable(new File(cacheDir, ".table"));
        } else {
            this.cacheDir = null;
            this.loadedHandlers = null;
            this.controlTable = null;
        }
    }

    @Nullable
    public Handler handler(@NotNull ByteString fileId) {
        if (!enabled) return null;

        String hexId = Utils.bytesToHex(fileId);
        return loadedHandlers.computeIfAbsent(hexId, id -> {
            try {
                return new Handler(id);
            } catch (IOException ex) {
                LOGGER.fatal("Failed creating cache handler for " + hexId, ex);
                return null;
            }
        });
    }

    public interface CacheConfiguration {
        boolean cacheEnabled();

        @NotNull
        File cacheDir();
    }

    private class ControlTable {
        private final List<CacheEntry> entries = new ArrayList<>();
        private final RandomAccessFile file;

        private ControlTable(@NotNull File controlFile) throws IOException {
            if (!controlFile.exists()) {
                if (controlFile.createNewFile()) file = new RandomAccessFile(controlFile, "rwd");
                else throw new IOException("Failed creating cache control file!");
                save();
            } else {
                file = new RandomAccessFile(controlFile, "rwd");
                file.seek(0);

                long read = 0;
                while (read < file.length()) {
                    entries.add(new CacheEntry(file));
                    read += file.getFilePointer();
                }
            }
        }

        private void save() throws IOException {
            file.seek(0);
            for (CacheEntry entry : entries)
                entry.writeTo(file);
        }

        boolean has(@NotNull String fileId, int chunk) {
            for (CacheEntry entry : entries)
                if (fileId.equals(entry.hexId))
                    return entry.has(chunk);

            return false;
        }

        public boolean hasHeaders(@NotNull String fileId) {
            for (CacheEntry entry : entries)
                if (fileId.equals(entry.hexId))
                    return true;

            return false;
        }

        private void safeSave() {
            try {
                save();
            } catch (IOException ex) {
                LOGGER.warn("Failed saving cache control file!", ex);
            }
        }

        void writtenChunk(@NotNull String fileId, int index) {
            for (CacheEntry entry : entries) {
                if (fileId.equals(entry.hexId))
                    entry.writtenChunk(index);
            }

            safeSave();
        }

        void writeHeaders(@NotNull String fileId, byte[] headersId, byte[][] headersData, short chunksCount) {
            entries.add(new CacheEntry(fileId, headersId, headersData, chunksCount));
        }

        public void remove(@NotNull String fileId) {
            Iterator<CacheEntry> iterator = entries.iterator();
            while (iterator.hasNext()) {
                if (fileId.equals(iterator.next().hexId)) {
                    iterator.remove();
                    safeSave();
                    return;
                }
            }
        }

        void requestHeaders(@NotNull String fileId, @NotNull AudioFile file) {
            for (CacheEntry entry : entries) {
                if (fileId.equals(entry.hexId)) {
                    entry.requestHeaders(file);
                    return;
                }
            }

            file.cacheFailedHeader(file);
        }

        private class CacheEntry {
            private final String hexId;
            private final ByteString gid;
            private final byte[] headersId;
            private final byte[][] headersData;
            private final boolean[] chunks;

            CacheEntry(@NotNull String hexId, byte[] headersId, byte[][] headersData, short chunksSize) {
                this.hexId = hexId;
                this.gid = ByteString.copyFrom(new BigInteger(hexId, 16).toByteArray());
                this.headersId = headersId;
                this.headersData = headersData;
                this.chunks = new boolean[chunksSize];
            }

            CacheEntry(@NotNull DataInput in) throws IOException {
                byte[] buffer = new byte[20];
                in.readFully(buffer);
                gid = ByteString.copyFrom(buffer);
                hexId = Utils.bytesToHex(buffer);

                short headers = in.readShort();
                headersId = new byte[headers];
                headersData = new byte[headers][];
                for (int i = 0; i < headers; i++) {
                    short length = (short) (in.readShort() - 1);
                    headersId[i] = in.readByte();
                    headersData[i] = new byte[length];
                    in.readFully(headersData[i]);
                }

                short chunksCount = in.readShort();
                chunks = new boolean[chunksCount];
                for (int i = 0; i < chunksCount; i++)
                    chunks[i] = in.readBoolean();
            }

            boolean has(int chunk) {
                return chunks[chunk];
            }

            private void writeTo(@NotNull DataOutput out) throws IOException {
                out.write(gid.toByteArray());

                out.writeShort(headersId.length);
                for (int i = 0; i < headersId.length; i++) {
                    short length = (short) (1 + headersData[i].length);
                    out.writeShort(length);
                    out.writeByte(headersId[i]);
                    out.write(headersData[i]);
                }

                out.writeShort(chunks.length);
                for (boolean chunk : chunks)
                    out.writeBoolean(chunk);
            }

            void requestHeaders(@NotNull AudioFile file) {
                for (int i = 0; i < headersId.length; i++)
                    file.writeHeader(headersId[i], headersData[i], true);

                file.headerEnd(true);
            }

            void writtenChunk(int index) {
                chunks[index] = true;
            }
        }
    }

    public class Handler implements Closeable {
        private final RandomAccessFile cache;
        private final String fileId;

        private Handler(@NotNull String fileId) throws IOException {
            this.fileId = fileId;

            File file = new File(cacheDir, fileId);
            if (!file.exists() && !file.createNewFile())
                throw new IOException("Failed creating cache file for " + fileId);

            cache = new RandomAccessFile(file, "rw");
        }

        public boolean has(int chunk) {
            return controlTable.has(fileId, chunk);
        }

        @Override
        public void close() throws IOException {
            cache.close();
        }

        public void requestHeaders(@NotNull AudioFile fetch) {
            executorService.execute(() -> controlTable.requestHeaders(fileId, fetch));
        }

        public void requestChunk(int index, @NotNull AudioFile file) {
            executorService.execute(() -> {
                try {
                    cache.seek(index * CHUNK_SIZE);
                    byte[] buffer = new byte[CHUNK_SIZE];
                    cache.readFully(buffer);
                    file.writeChunk(buffer, index, true);
                } catch (IOException ex) {
                    LOGGER.fatal("Failed reading chunk, index: " + index, ex);
                    remove();
                    file.cacheFailedChunk(index, file);
                }
            });
        }

        public void write(byte[] buffer, int index) throws IOException {
            cache.seek(index * CHUNK_SIZE);
            cache.write(buffer);
            controlTable.writtenChunk(fileId, index);
        }

        public void remove() {
            controlTable.remove(fileId);
        }

        public void writeHeaders(byte[] headersId, byte[][] headersData, short chunksCount) {
            controlTable.writeHeaders(fileId, headersId, headersData, chunksCount);
        }

        public boolean hasHeaders() {
            return controlTable.hasHeaders(fileId);
        }
    }
}
