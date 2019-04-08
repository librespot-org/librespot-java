package xyz.gianlu.librespot.cache;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.player.GeneralWritableStream;
import xyz.gianlu.librespot.player.StreamId;

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

import static xyz.gianlu.librespot.player.feeders.storage.ChannelManager.CHUNK_SIZE;


/**
 * @author Gianlu
 */
public class CacheManager implements Closeable {
    private static final long CLEAN_UP_THRESHOLD = TimeUnit.DAYS.toMillis(7);
    private static final Logger LOGGER = Logger.getLogger(CacheManager.class);
    private static final byte HEADER_TIMESTAMP = (byte) 0b11111111;
    private final boolean enabled;
    private final File parent;
    private final Map<String, Handler> fileHandlers = new ConcurrentHashMap<>();
    private final Map<String, Handler> episodeHandlers = new ConcurrentHashMap<>();
    private final Connection table;

    public CacheManager(@NotNull Configuration conf) throws IOException {
        this.enabled = conf.cacheEnabled();
        if (!enabled) {
            parent = null;
            table = null;
            return;
        }

        this.parent = conf.cacheDir();

        if (!parent.exists() && !parent.mkdir())
            throw new IOException("Couldn't create cache directory!");

        try {
            File tableFile = new File(parent, "table");
            this.table = DriverManager.getConnection("jdbc:h2:" + tableFile.getAbsolutePath());
            createTablesIfNeeded();

            deleteCorruptedEntries();
            if (conf.doCleanUp()) doCleanUp();
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
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

    private static boolean exists(@NotNull File parent, @NotNull String hex) {
        String firstLevel = hex.substring(0, 2);
        String secondLevel = hex.substring(2, 4);

        parent = new File(parent, "/" + firstLevel + "/" + secondLevel + "/");
        return new File(parent, hex).exists();
    }

    private void deleteCorruptedEntries() throws SQLException, IOException {
        if (!enabled) return;

        List<String> toRemove = new ArrayList<>();
        try (PreparedStatement statement = table.prepareStatement("SELECT DISTINCT streamId FROM Headers")) {
            ResultSet set = statement.executeQuery();
            while (set.next()) {
                String streamId = set.getString("streamId");
                if (!exists(parent, streamId))
                    toRemove.add(streamId);
            }
        }

        for (String streamId : toRemove)
            remove(streamId);
    }

    private void doCleanUp() throws SQLException, IOException {
        if (!enabled) return;

        try (PreparedStatement statement = table.prepareStatement("SELECT streamId, value FROM Headers WHERE id=?")) {
            statement.setString(1, Utils.byteToHex(HEADER_TIMESTAMP));

            ResultSet set = statement.executeQuery();
            while (set.next()) {
                long timestamp = Long.parseLong(set.getString("value"), 16) * 1000;
                if (System.currentTimeMillis() - timestamp > CLEAN_UP_THRESHOLD)
                    remove(set.getString("streamId"));
            }
        }
    }

    private void remove(@NotNull String streamId) throws SQLException, IOException {
        if (!enabled) return;

        try (PreparedStatement statement = table.prepareStatement("DELETE FROM Headers WHERE streamId=?")) {
            statement.setString(1, streamId);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = table.prepareStatement("DELETE FROM Chunks WHERE streamId=?")) {
            statement.setString(1, streamId);
            statement.executeUpdate();
        }

        File file = getCacheFile(parent, streamId);
        if (file.exists() && !file.delete())
            LOGGER.warn("Couldn't delete cache file: " + file.getAbsolutePath());

        LOGGER.trace(String.format("Removed %s from cache.", streamId));
    }

    private void createTablesIfNeeded() throws SQLException {
        if (!enabled) return;

        try (Statement statement = table.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS Chunks ( `streamId` VARCHAR NOT NULL, `chunkIndex` INTEGER NOT NULL, `available` INTEGER NOT NULL, PRIMARY KEY(`streamId`,`chunkIndex`) )");
        }

        try (Statement statement = table.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS Headers ( `streamId` VARCHAR NOT NULL, `id` VARCHAR NOT NULL, `value` VARCHAR NOT NULL, PRIMARY KEY(`streamId`,`id`) )");
        }
    }

    @Override
    public void close() throws IOException {
        for (Handler handler : new ArrayList<>(fileHandlers.values()))
            handler.close();
    }

    @Nullable
    public Handler forWhatever(@NotNull StreamId id) throws IOException {
        if (!enabled) return null;

        if (id.isEpisode()) return forEpisode(id.getEpisodeGid());
        else return forFileId(id.getFileId());
    }

    @Nullable
    public Handler forFileId(@NotNull String fileId) throws IOException {
        if (!enabled) return null;

        Handler handler = fileHandlers.get(fileId);
        if (handler == null) {
            handler = new Handler(fileId, getCacheFile(parent, fileId));
            fileHandlers.put(fileId, handler);
        }

        return handler;
    }

    @Nullable
    public Handler forEpisode(@NotNull String gid) throws IOException {
        if (!enabled) return null;

        Handler handler = episodeHandlers.get(gid);
        if (handler == null) {
            handler = new Handler(gid, getCacheFile(parent, gid));
            episodeHandlers.put(gid, handler);
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
        private final String streamId;
        private final RandomAccessFile io;
        private boolean updatedTimestamp = false;

        private Handler(@NotNull String streamId, @NotNull File file) throws IOException {
            this.streamId = streamId;

            if (!file.exists() && !file.createNewFile())
                throw new IOException("Couldn't create cache file!");

            this.io = new RandomAccessFile(file, "rwd");
        }

        private void updateTimestamp() {
            if (updatedTimestamp) return;

            try (PreparedStatement statement = table.prepareStatement("MERGE INTO Headers (streamId, id, value) VALUES (?, ?, ?)")) {
                statement.setString(1, streamId);
                statement.setString(2, Utils.byteToHex(HEADER_TIMESTAMP));
                statement.setString(3, Utils.bytesToHex(BigInteger.valueOf(System.currentTimeMillis() / 1000).toByteArray()));

                statement.executeUpdate();
                updatedTimestamp = true;
            } catch (SQLException ex) {
                LOGGER.warn("Failed updating timestamp for " + streamId, ex);
            }
        }

        public void setHeader(byte id, byte[] value) throws SQLException {
            try (PreparedStatement statement = table.prepareStatement("MERGE INTO Headers (streamId, id, value) VALUES (?, ?, ?)")) {
                statement.setString(1, streamId);
                statement.setString(2, Utils.byteToHex(id));
                statement.setString(3, Utils.bytesToHex(value));

                statement.executeUpdate();
            } finally {
                updateTimestamp();
            }
        }

        @NotNull
        public List<Header> getAllHeaders() throws SQLException {
            try (PreparedStatement statement = table.prepareStatement("SELECT id, value FROM Headers WHERE streamId=?")) {
                statement.setString(1, streamId);

                List<Header> headers = new ArrayList<>();
                ResultSet set = statement.executeQuery();
                while (set.next())
                    headers.add(new Header(set.getString("id"), set.getString("value")));

                return headers;
            }
        }

        @Nullable
        public byte[] getHeader(byte id) throws SQLException {
            try (PreparedStatement statement = table.prepareStatement("SELECT value FROM Headers WHERE streamId=? AND id=? LIMIT 1")) {
                statement.setString(1, streamId);
                statement.setString(2, Utils.byteToHex(id));

                ResultSet set = statement.executeQuery();
                if (!set.next()) return null;

                return Utils.hexToBytes(set.getString("value"));
            }
        }

        public boolean hasChunk(int index) throws SQLException, IOException {
            updateTimestamp();

            synchronized (io) {
                if (io.length() < (index + 1) * CHUNK_SIZE) return false;
            }

            try (PreparedStatement statement = table.prepareStatement("SELECT available FROM Chunks WHERE streamId=? AND chunkIndex=? LIMIT 1")) {
                statement.setString(1, streamId);
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

            synchronized (io) {
                io.seek(index * CHUNK_SIZE);

                byte[] buffer = new byte[CHUNK_SIZE];
                int read = io.read(buffer);
                if (read != buffer.length)
                    throw new IOException(String.format("Couldn't read full chunk, read: %d, needed: %d", read, buffer.length));

                return buffer;
            }
        }

        public void writeChunk(byte[] buffer, int index) throws IOException, SQLException {
            synchronized (io) {
                io.seek(index * CHUNK_SIZE);
                io.write(buffer);
            }

            try (PreparedStatement statement = table.prepareStatement("MERGE INTO Chunks (streamId, chunkIndex, available) VALUES (?, ?, ?)")) {
                statement.setString(1, streamId);
                statement.setInt(2, index);
                statement.setInt(3, 1);

                statement.executeUpdate();
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
