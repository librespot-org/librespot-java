package xyz.gianlu.librespot.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static xyz.gianlu.librespot.player.ChannelManager.CHUNK_SIZE;


/**
 * @author Gianlu
 */
public class CacheManager {
    static final byte BYTE_CREATED_AT = 0b1111111;
    private static final Logger LOGGER = Logger.getLogger(CacheManager.class);
    private static final long CLEAN_UP_THRESHOLD = TimeUnit.DAYS.toMillis(7);
    private final File cacheDir;
    private final boolean enabled;
    private final Map<String, Handler> loadedHandlers;
    private final ControlTable controlTable;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    CacheManager(@NotNull CacheConfiguration conf) throws IOException {
        this.enabled = conf.cacheEnabled();
        if (enabled) {
            this.loadedHandlers = new HashMap<>();
            this.cacheDir = conf.cacheDir();
            if (!cacheDir.exists() && !cacheDir.mkdir())
                throw new IllegalStateException("Cannot create cache dir!");

            this.controlTable = new ControlTable(new File(cacheDir, ".table"));
            if (conf.doCleanUp()) controlTable.cleanOldTracks();
        } else {
            this.cacheDir = null;
            this.loadedHandlers = null;
            this.controlTable = null;
        }
    }

    @Nullable
    Handler handler(@NotNull ByteString fileId) {
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

        boolean doCleanUp();
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
                int count = file.readInt();
                for (int i = 0; i < count; i++)
                    entries.add(new CacheEntry(file));
            }
        }

        private void cleanOldTracks() {
            Iterator<CacheEntry> iterator = entries.iterator();
            while (iterator.hasNext()) {
                CacheEntry entry = iterator.next();
                if (System.currentTimeMillis() - entry.getCreatedAtMillis() > CLEAN_UP_THRESHOLD) {
                    entry.deleteFile();
                    iterator.remove();
                }
            }

            safeSave();
        }

        private void save() throws IOException {
            file.seek(0);
            file.writeInt(entries.size());
            for (CacheEntry entry : entries)
                entry.writeTo(file);
        }

        boolean has(@NotNull String fileId, int chunk) {
            for (CacheEntry entry : entries)
                if (fileId.equals(entry.hexId))
                    return entry.has(chunk);

            return false;
        }

        boolean hasHeaders(@NotNull String fileId) {
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
                CacheEntry entry = iterator.next();
                if (fileId.equals(entry.hexId)) {
                    entry.deleteFile();
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
                this.gid = ByteString.copyFrom(Utils.hexToBytes(hexId));
                this.headersId = headersId;
                this.headersData = headersData;
                this.chunks = new boolean[chunksSize];
            }

            CacheEntry(@NotNull DataInput in) throws IOException {
                byte[] buffer = new byte[in.readShort()];
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
                byte[] gidBytes = gid.toByteArray();
                out.writeShort(gidBytes.length);
                out.write(gidBytes);

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

            @Nullable
            byte[] findHeaderData(byte id) {
                for (int i = 0; i < headersId.length; i++)
                    if (headersId[i] == id)
                        return headersData[i];

                return null;
            }

            long getCreatedAtMillis() {
                byte[] createdAtBytes = findHeaderData(BYTE_CREATED_AT);
                if (createdAtBytes == null) {
                    LOGGER.warn("Missing CREATED_AT header!");
                    return System.currentTimeMillis();
                }

                return new BigInteger(createdAtBytes).longValue() * 1000;
            }

            void deleteFile() {
                File toDelete = new File(cacheDir, hexId);
                if (toDelete.delete()) {
                    LOGGER.trace("Deleted cached track: " + hexId);
                } else {
                    LOGGER.warn("Failed deleting cached track: " + toDelete);
                    toDelete.deleteOnExit();
                }
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

        boolean has(int chunk) {
            return controlTable.has(fileId, chunk);
        }

        @Override
        public void close() throws IOException {
            cache.close();
        }

        void requestHeaders(@NotNull AudioFile fetch) {
            executorService.execute(() -> controlTable.requestHeaders(fileId, fetch));
        }

        void requestChunk(int index, @NotNull AudioFile file) {
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

        void writeHeaders(byte[] headersId, byte[][] headersData, short chunksCount) {
            controlTable.writeHeaders(fileId, headersId, headersData, chunksCount);
        }

        boolean hasHeaders() {
            return controlTable.hasHeaders(fileId);
        }
    }
}
