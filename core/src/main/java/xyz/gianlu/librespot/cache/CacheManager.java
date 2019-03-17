package xyz.gianlu.librespot.cache;

import com.google.protobuf.ByteString;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.player.AudioFileFetch;
import xyz.gianlu.librespot.player.GeneralWritableStream;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static xyz.gianlu.librespot.player.ChannelManager.CHUNK_SIZE;


/**
 * @author Gianlu
 */
public class CacheManager implements Closeable {
    private static final long CLEAN_UP_THRESHOLD = TimeUnit.DAYS.toMillis(7);
    private static final Logger LOGGER = Logger.getLogger(CacheManager.class);
    private final boolean enabled;
    private final File parent;
    private final Map<ByteString, Handler> handlers = new ConcurrentHashMap<>();
    private final Connection table;

    public CacheManager(@NotNull Configuration conf) throws IOException {
        this.enabled = conf.cacheEnabled();
        this.parent = conf.cacheDir();

        if (!parent.exists() && !parent.mkdir())
            throw new IOException("Couldn't create cache directory!");

        try {
            File tableFile = new File(parent, "table.sqlite");
            this.table = DriverManager.getConnection("jdbc:sqlite:" + tableFile.getAbsolutePath());
            createTablesIfNeeded();

            if (conf.doCleanUp()) doCleanUp();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
    }

    @NotNull
    private static File getCacheFile(@NotNull File parent, @NotNull ByteString fileId) throws IOException {
        return getCacheFile(parent, Utils.bytesToHex(fileId));
    }

    @NotNull
    private static File getCacheFile(@NotNull File parent, @NotNull String hex) throws IOException {
        String firstLevel = hex.substring(0, 2);
        String secondLevel = hex.substring(2, 4);

        parent = new File(parent, "/" + firstLevel + "/" + secondLevel + "/");
        if (!parent.exists() && !parent.mkdirs())
            throw new IOException("Couldn't create cache directories!");

        return new File(parent, hex);
    }

    private void doCleanUp() throws SQLException, IOException {
        try (PreparedStatement statement = table.prepareStatement("SELECT fileId, value FROM Headers WHERE id=?")) {
            statement.setString(1, Utils.byteToHex(AudioFileFetch.HEADER_TIMESTAMP));

            ResultSet set = statement.executeQuery();
            while (set.next()) {
                long timestamp = Long.parseLong(set.getString("value"), 16) * 1000;
                if (System.currentTimeMillis() - timestamp > CLEAN_UP_THRESHOLD)
                    remove(set.getString("fileId"));
            }
        }
    }

    private void remove(@NotNull String fileIdHex) throws SQLException, IOException {
        try (PreparedStatement statement = table.prepareStatement("DELETE FROM Headers WHERE fileId=?")) {
            statement.setString(1, fileIdHex);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = table.prepareStatement("DELETE FROM Chunks WHERE fileId=?")) {
            statement.setString(1, fileIdHex);
            statement.executeUpdate();
        }

        File file = getCacheFile(parent, fileIdHex);
        if (!file.delete())
            LOGGER.warn("Couldn't delete cache file: " + file.getAbsolutePath());

        LOGGER.trace(String.format("Removed %s from cache.", fileIdHex));
    }

    private void createTablesIfNeeded() throws SQLException {
        try (Statement statement = table.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS Chunks ( `fileId` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, `available` INTEGER NOT NULL, PRIMARY KEY(`fileId`,`chunkIndex`) )");
        }

        try (Statement statement = table.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS Headers ( `fileId` TEXT NOT NULL, `id` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`fileId`,`id`) )");
        }
    }

    @Override
    public void close() throws IOException {
        for (Handler handler : new ArrayList<>(handlers.values()))
            handler.close();
    }

    @Nullable
    public CacheManager.Handler forFileId(@NotNull ByteString fileId) throws IOException {
        if (!enabled) return null;

        Handler handler = handlers.get(fileId);
        if (handler == null) {
            handler = new Handler(fileId);
            handlers.put(fileId, handler);
        }

        return handler;
    }

    public interface Configuration {
        boolean cacheEnabled();

        @NotNull File cacheDir();

        boolean doCleanUp();
    }

    public static class Header {
        public final byte id;
        public final byte[] value;

        private Header(@NotNull String id, @NotNull String value) {
            this.id = Utils.hexToBytes(id)[0];
            this.value = Utils.hexToBytes(value);
        }

        @Nullable
        public static Header find(List<Header> headers, byte id) {
            for (Header header : headers)
                if (header.id == id)
                    return header;

            return null;
        }
    }

    public class Handler implements Closeable {
        private final File file;
        private final ByteString fileId;
        private final RandomAccessFile io;
        private boolean updatedTimestamp = false;

        private Handler(@NotNull ByteString fileId) throws IOException {
            this.fileId = fileId;
            this.file = getCacheFile(parent, fileId);

            if (!file.exists() && !file.createNewFile())
                throw new IOException("Couldn't create cache file!");

            this.io = new RandomAccessFile(file, "rwd");
        }

        private void updateTimestamp() {
            if (updatedTimestamp) return;

            try (PreparedStatement statement = table.prepareStatement("INSERT OR REPLACE INTO Headers (fileId, id, value) VALUES (?, ?, ?)")) {
                statement.setString(1, Utils.bytesToHex(fileId));
                statement.setString(2, Utils.byteToHex(AudioFileFetch.HEADER_TIMESTAMP));
                statement.setString(3, Utils.bytesToHex(BigInteger.valueOf(System.currentTimeMillis() / 1000).toByteArray()));

                statement.executeUpdate();
                updatedTimestamp = true;
            } catch (SQLException ex) {
                LOGGER.warn("Failed updating timestamp for " + Utils.bytesToHex(fileId), ex);
            }
        }

        public void setHeader(byte id, byte[] value) throws SQLException {
            updateTimestamp();

            try (PreparedStatement statement = table.prepareStatement("INSERT OR REPLACE INTO Headers (fileId, id, value) VALUES (?, ?, ?)")) {
                statement.setString(1, Utils.bytesToHex(fileId));
                statement.setString(2, Utils.byteToHex(id));
                statement.setString(3, Utils.bytesToHex(value));

                statement.executeUpdate();
            }
        }

        @NotNull
        public List<Header> getAllHeaders() throws SQLException {
            updateTimestamp();

            try (PreparedStatement statement = table.prepareStatement("SELECT id, value FROM Headers WHERE fileId=?")) {
                statement.setString(1, Utils.bytesToHex(fileId));

                List<Header> headers = new ArrayList<>();
                ResultSet set = statement.executeQuery();
                while (set.next())
                    headers.add(new Header(set.getString("id"), set.getString("value")));

                return headers;
            }
        }

        @Nullable
        public byte[] getHeader(byte id) throws SQLException {
            updateTimestamp();

            try (PreparedStatement statement = table.prepareStatement("SELECT value FROM Headers WHERE fileId=? AND id=? LIMIT 1")) {
                statement.setString(1, Utils.bytesToHex(fileId));
                statement.setString(2, Utils.byteToHex(id));

                ResultSet set = statement.executeQuery();
                if (!set.next()) return null;

                return Utils.hexToBytes(set.getString("value"));
            }
        }

        public boolean hasChunk(int index) throws SQLException, IOException {
            updateTimestamp();

            if (io.length() < (index + 1) * CHUNK_SIZE) return false;

            try (PreparedStatement statement = table.prepareStatement("SELECT available FROM Chunks WHERE fileId=? AND chunkIndex=? LIMIT 1")) {
                statement.setString(1, Utils.bytesToHex(fileId));
                statement.setInt(2, index);

                ResultSet set = statement.executeQuery();
                if (!set.next()) return false;

                return set.getInt("available") == 1;
            }
        }

        public void readChunk(int index, @NotNull GeneralWritableStream stream) throws IOException {
            stream.writeChunk(readChunk(index), index, true);
        }

        public byte[] readChunk(int index) throws IOException {
            updateTimestamp();

            io.seek(index * CHUNK_SIZE);

            byte[] buffer = new byte[CHUNK_SIZE];
            int read = io.read(buffer);
            if (read != buffer.length)
                throw new IOException(String.format("Couldn't read full chunk, read: %d, needed: %d", read, buffer.length));

            return buffer;
        }

        public void writeChunk(byte[] buffer, int index) throws IOException, SQLException {
            updateTimestamp();

            io.seek(index * CHUNK_SIZE);
            io.write(buffer);

            try (PreparedStatement statement = table.prepareStatement("INSERT OR REPLACE INTO Chunks (fileId, chunkIndex, available) VALUES (?, ?, ?)")) {
                statement.setString(1, Utils.bytesToHex(fileId));
                statement.setInt(2, index);
                statement.setInt(3, 1);

                statement.executeUpdate();
            }
        }

        @Override
        public void close() throws IOException {
            io.close();
            handlers.remove(fileId);
        }
    }
}
