package xyz.gianlu.librespot.cache;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.GeneralWritableStream;
import xyz.gianlu.librespot.player.StreamId;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static xyz.gianlu.librespot.player.feeders.storage.ChannelManager.CHUNK_SIZE;


/**
 * @author Gianlu
 */
public class CacheManager implements Closeable {
    private static final long CLEAN_UP_THRESHOLD = TimeUnit.DAYS.toMillis(7);
    private static final Logger LOGGER = Logger.getLogger(CacheManager.class);
    private static final byte HEADER_TIMESTAMP = (byte) 0b11111110;
    private final File parent;
    private final CacheJournal journal;
    private final Map<String, Handler> fileHandlers = new ConcurrentHashMap<>();

    public CacheManager(@NotNull Configuration conf) throws IOException {
        if (!conf.cacheEnabled()) {
            parent = null;
            journal = null;
            return;
        }

        this.parent = conf.cacheDir();
        if (!parent.exists() && !parent.mkdir())
            throw new IOException("Couldn't create cache directory!");

        journal = new CacheJournal(parent);

        new Thread(() -> {
            try {
                List<String> entries = journal.getEntries();
                Iterator<String> iter = entries.iterator();
                while (iter.hasNext()) {
                    String id = iter.next();
                    if (!exists(parent, id)) {
                        iter.remove();
                        journal.remove(id);
                    }
                }

                if (conf.doCleanUp()) {
                    for (String id : entries) {
                        JournalHeader header = journal.getHeader(id, HEADER_TIMESTAMP);
                        if (header == null) continue;

                        long timestamp = new BigInteger(header.value).longValue();
                        if (System.currentTimeMillis() - timestamp > CLEAN_UP_THRESHOLD)
                            remove(id);
                    }
                }

                LOGGER.info(String.format("There are %d cached entries.", entries.size()));
            } catch (IOException ex) {
                LOGGER.warn("Failed performing maintenance operations.", ex);
            }
        }, "cache-maintenance").start();
    }

    @NotNull
    private static File getCacheFile(@NotNull File parent, @NotNull String hex) throws IOException {
        String dir = hex.substring(0, 2);
        parent = new File(parent, "/" + dir + "/");
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Couldn't create cache directories!");
        return new File(parent, hex);
    }

    private static boolean exists(@NotNull File parent, @NotNull String hex) {
        String dir = hex.substring(0, 2);
        parent = new File(parent, "/" + dir + "/");
        return new File(parent, hex).exists();
    }

    private void remove(@NotNull String streamId) throws IOException {
        journal.remove(streamId);

        File file = getCacheFile(parent, streamId);
        if (file.exists() && !file.delete())
            LOGGER.warn("Couldn't delete cache file: " + file.getAbsolutePath());

        LOGGER.trace(String.format("Removed %s from cache.", streamId));
    }

    @Override
    public void close() throws IOException {
        for (Handler handler : new ArrayList<>(fileHandlers.values()))
            handler.close();

        if (journal != null) journal.close();
    }

    @Nullable
    public Handler getHandler(@NotNull String id) throws IOException {
        if (journal == null) return null;

        Handler handler = fileHandlers.get(id);
        if (handler == null) {
            handler = new Handler(id, getCacheFile(parent, id));
            fileHandlers.put(id, handler);
        }

        return handler;
    }

    @Nullable
    public Handler getHandler(@NotNull StreamId streamId) throws IOException {
        return getHandler(streamId.isEpisode() ? streamId.getEpisodeGid() : streamId.getFileId());
    }

    public interface Configuration {
        boolean cacheEnabled();

        @NotNull File cacheDir();

        boolean doCleanUp();
    }

    public class Handler implements Closeable {
        private final String streamId;
        private final RandomAccessFile io;
        private boolean updatedTimestamp = false;

        private Handler(@NotNull String streamId, @NotNull File file) throws IOException {
            this.streamId = streamId;

            if (!file.exists() && !file.createNewFile())
                throw new IOException("Couldn't create cache file!");

            this.io = new RandomAccessFile(file, "rwd");

            journal.createIfNeeded(streamId);
        }

        private void updateTimestamp() {
            if (updatedTimestamp) return;

            try {
                journal.setHeader(streamId, HEADER_TIMESTAMP, BigInteger.valueOf(System.currentTimeMillis() / 1000).toByteArray());
                updatedTimestamp = true;
            } catch (IOException ex) {
                LOGGER.warn("Failed updating timestamp for " + streamId, ex);
            }
        }

        public void setHeader(byte id, byte[] value) throws IOException {
            try {
                journal.setHeader(streamId, id, value);
            } finally {
                updateTimestamp();
            }
        }

        @NotNull
        public List<JournalHeader> getAllHeaders() throws IOException {
            return journal.getHeaders(streamId);
        }

        @Nullable
        public byte[] getHeader(byte id) throws IOException {
            JournalHeader header = journal.getHeader(streamId, id);
            return header == null ? null : header.value;
        }

        public boolean hasChunk(int index) throws IOException {
            updateTimestamp();

            synchronized (io) {
                if (io.length() < (index + 1) * CHUNK_SIZE) return false;
            }

            return journal.hasChunk(streamId, index);
        }

        public void readChunk(int index, @NotNull GeneralWritableStream stream) throws IOException {
            stream.writeChunk(readChunk(index), index, true);
        }

        public byte[] readChunk(int index) throws IOException {
            updateTimestamp();

            synchronized (io) {
                io.seek(index * CHUNK_SIZE);

                byte[] buffer = new byte[CHUNK_SIZE];
                int read = io.read(buffer);
                if (read != buffer.length)
                    throw new IOException(String.format("Couldn't read full chunk, read: %d, needed: %d", read, buffer.length));

                return buffer;
            }
        }

        public void writeChunk(byte[] buffer, int index) throws IOException {
            synchronized (io) {
                io.seek(index * CHUNK_SIZE);
                io.write(buffer);
            }

            try {
                journal.setChunk(streamId, index, true);
            } finally {
                updateTimestamp();
            }
        }

        @Override
        public void close() throws IOException {
            fileHandlers.remove(streamId);
            synchronized (io) {
                io.close();
            }
        }
    }
}
