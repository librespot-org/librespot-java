/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.audio.GeneralWritableStream;
import xyz.gianlu.librespot.audio.StreamId;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static xyz.gianlu.librespot.audio.storage.ChannelManager.CHUNK_SIZE;


/**
 * @author Gianlu
 */
public class CacheManager implements Closeable {
    private static final long CLEAN_UP_THRESHOLD = TimeUnit.DAYS.toMillis(7);
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheManager.class);
    /**
     * The header indicating when the file was last read or written to.
     */
    private static final int HEADER_TIMESTAMP = 254;
    /**
     * The header indicating the hash of the first chunk of the file.
     */
    private static final int HEADER_HASH = 253;
    private final File parent;
    private final CacheJournal journal;
    private final Map<String, Handler> fileHandlers = new ConcurrentHashMap<>();

    public CacheManager(@NotNull Session.Configuration conf) throws IOException {
        if (!conf.cacheEnabled) {
            parent = null;
            journal = null;
            return;
        }

        this.parent = conf.cacheDir;
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

                if (conf.doCacheCleanUp) {
                    for (String id : entries) {
                        JournalHeader header = journal.getHeader(id, HEADER_TIMESTAMP);
                        if (header == null) continue;

                        long timestamp = new BigInteger(header.value).longValue() * 1000;
                        if (System.currentTimeMillis() - timestamp > CLEAN_UP_THRESHOLD)
                            remove(id);
                    }
                }

                LOGGER.info("There are {} cached entries.", entries.size());
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

        LOGGER.trace("Removed {} from cache.", streamId);
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

    public static class BadChunkHashException extends Exception {
        BadChunkHashException(@NotNull String streamId, byte[] expected, byte[] actual) {
            super(String.format("Failed verifying chunk hash for %s, expected: %s, actual: %s",
                    streamId, Utils.bytesToHex(expected), Utils.bytesToHex(actual)));
        }
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

        public void setHeader(int id, byte[] value) throws IOException {
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

        /**
         * Checks if the chunk is present in the cache, WITHOUT checking the hash.
         *
         * @param index The index of the chunk
         * @return Whether the chunk is available
         */
        public boolean hasChunk(int index) throws IOException {
            updateTimestamp();

            synchronized (io) {
                if (io.length() < (long) (index + 1) * CHUNK_SIZE)
                    return false;
            }

            return journal.hasChunk(streamId, index);
        }

        public void readChunk(int index, @NotNull GeneralWritableStream stream) throws IOException, BadChunkHashException {
            stream.writeChunk(readChunk(index), index, true);
        }

        /**
         * Reads the given chunk.
         *
         * @param index The index of the chunk
         * @return The buffer containing the content of the chunk
         * @throws BadChunkHashException If {@code index == 0} and the hash doesn't match
         */
        public byte[] readChunk(int index) throws IOException, BadChunkHashException {
            updateTimestamp();

            synchronized (io) {
                io.seek((long) index * CHUNK_SIZE);

                byte[] buffer = new byte[CHUNK_SIZE];
                int read = io.read(buffer);
                if (read != buffer.length)
                    throw new IOException(String.format("Couldn't read full chunk, read: %d, needed: %d", read, buffer.length));

                if (index == 0) {
                    JournalHeader header = journal.getHeader(streamId, HEADER_HASH);
                    if (header != null) {
                        try {
                            MessageDigest digest = MessageDigest.getInstance("MD5");
                            byte[] hash = digest.digest(buffer);
                            if (!Arrays.equals(header.value, hash)) {
                                journal.setChunk(streamId, index, false);
                                throw new BadChunkHashException(streamId, header.value, hash);
                            }
                        } catch (NoSuchAlgorithmException ex) {
                            LOGGER.error("Failed initializing MD5 digest.", ex);
                        }
                    }
                }

                return buffer;
            }
        }

        public void writeChunk(byte[] buffer, int index) throws IOException {
            synchronized (io) {
                io.seek((long) index * CHUNK_SIZE);
                io.write(buffer);
            }

            try {
                journal.setChunk(streamId, index, true);

                if (index == 0) {
                    try {
                        MessageDigest digest = MessageDigest.getInstance("MD5");
                        byte[] hash = digest.digest(buffer);
                        journal.setHeader(streamId, HEADER_HASH, hash);
                    } catch (NoSuchAlgorithmException ex) {
                        LOGGER.error("Failed initializing MD5 digest.", ex);
                    }
                }
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
