package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.librespot.spotify.Utils;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public CacheManager(@NotNull CacheConfiguration conf) throws IOException, ClassNotFoundException {
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
        private final HashMap<String, ArrayList<Integer>> map;
        private final File controlFile;

        private ControlTable(@NotNull File controlFile) throws IOException, ClassNotFoundException {
            this.controlFile = controlFile;

            if (!controlFile.exists()) {
                if (controlFile.createNewFile()) map = new HashMap<>();
                else throw new IOException("Failed creating cache control file!");
                save();
            } else {
                try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(controlFile))) {
                    // noinspection unchecked
                    map = (HashMap<String, ArrayList<Integer>>) in.readObject();
                }
            }
        }

        private void save() throws IOException {
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(controlFile))) {
                out.writeObject(map);
            }
        }

        boolean has(@NotNull String fileId, int chunk) {
            List<Integer> list = map.get(fileId);
            return list != null && list.contains(chunk);
        }

        private void safeSave() {
            try {
                save();
            } catch (IOException ex) {
                LOGGER.warn("Failed saving cache control file!", ex);
            }
        }

        void written(@NotNull String fileId, int index) {
            List<Integer> list = map.computeIfAbsent(fileId, s -> new ArrayList<>());
            list.add(index);
            safeSave();
        }

        public void remove(@NotNull String fileId) {
            map.remove(fileId);
            safeSave();
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

        public int requestSize() throws IOException {
            cache.seek(0);
            return cache.readInt() * 4;
        }

        public void requestChunk(int index, @NotNull AudioFile file) {
            executorService.execute(() -> {
                try {
                    cache.seek(index * CHUNK_SIZE + 4);
                    byte[] buffer = new byte[CHUNK_SIZE];
                    cache.readFully(buffer);
                    file.writeChunk(buffer, index);
                } catch (IOException ex) {
                    LOGGER.fatal("Failed reading chunk, index: " + index, ex);
                    remove();
                    file.cacheFailed(index, file);
                }
            });
        }

        public void writeSize(int size) throws IOException {
            size /= 4;
            cache.seek(0);
            cache.writeInt(size);
        }

        public void write(byte[] buffer, int index) throws IOException {
            cache.seek(index * CHUNK_SIZE + 4);
            cache.write(buffer);
            controlTable.written(fileId, index);
        }

        public void remove() {
            controlTable.remove(fileId);
        }
    }
}
