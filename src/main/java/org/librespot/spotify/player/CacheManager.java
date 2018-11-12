package org.librespot.spotify.player;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.librespot.spotify.player.ChannelManager.CHUNK_SIZE;


/**
 * @author Gianlu
 */
public class CacheManager {
    private static final Logger LOGGER = Logger.getLogger(CacheManager.class);
    private final File cacheDir;
    private final boolean enabled;
    private final Map<String, Handler> loadedHandlers;

    public CacheManager(@NotNull CacheConfiguration conf) {
        this.enabled = conf.cacheEnabled();
        if (enabled) {
            this.loadedHandlers = new HashMap<>();
            this.cacheDir = conf.cacheDir();
            if (!cacheDir.exists() && !cacheDir.mkdir())
                throw new IllegalStateException("Cannot created cache dir!");
        } else {
            this.cacheDir = null;
            this.loadedHandlers = null;
        }
    }

    @Nullable
    public Handler handler(@NotNull ByteString fileId) {
        if (!enabled) return null;
        return loadedHandlers.computeIfAbsent(Base64.getEncoder().encodeToString(fileId.toByteArray()), Handler::new);
    }

    @Nullable
    private RandomAccessFile readerFor(@NotNull String fileId) {
        File file = new File(cacheDir, fileId);
        if (file.exists()) {
            try {
                return new RandomAccessFile(file, "rw");
            } catch (FileNotFoundException ex) {
                LOGGER.fatal("Failed reading cache file: " + file, ex);
                return null;
            }
        } else {
            return null;
        }
    }

    public interface CacheConfiguration {
        boolean cacheEnabled();

        @NotNull
        File cacheDir();
    }

    public class Handler implements Closeable {
        private final RandomAccessFile in;

        private Handler(@NotNull String fileId) {
            in = readerFor(fileId);
        }

        public boolean has(int chunk) {
            return false;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        private byte[] readChunk(int index) {
            return new byte[CHUNK_SIZE];
        }

        public void requestChunk(int index, AudioFile file) throws IOException {
            file.writeChunk(readChunk(index), index);
        }
    }
}
