package xyz.gianlu.librespot.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A little journal implementation that stores information about the cache. The data is stored in this order:
 * - 40 bytes for the ID
 * - 2048 bytes for chunks
 * - 8 headers each of 1023 length + 1 byte for the ID
 * <p>
 * Headers are encoded to strings in order to take advantage of null terminators.
 *
 * @author Gianlu
 */
class CacheJournal implements Closeable {
    private static final int MAX_CHUNKS_SIZE = 2048;
    private static final int MAX_CHUNKS = MAX_CHUNKS_SIZE * 8;
    private static final int MAX_HEADERS = 8;
    private static final int MAX_HEADER_LENGTH = 1023;
    private static final int MAX_ID_LENGTH = 40;
    private static final int JOURNAL_ENTRY_SIZE = MAX_ID_LENGTH + MAX_CHUNKS_SIZE + (1 + MAX_HEADER_LENGTH) * MAX_HEADERS;
    private final RandomAccessFile io;
    private final Map<String, Entry> entries = Collections.synchronizedMap(new HashMap<>());

    CacheJournal(@NotNull File parent) throws FileNotFoundException {
        File file = new File(parent, "journal.dat");
        io = new RandomAccessFile(file, "rwd");
    }

    private static boolean checkId(@NotNull RandomAccessFile io, int first, @NotNull byte[] id) throws IOException {
        for (int i = 0; i < id.length; i++) {
            int read = i == 0 ? first : io.read();
            if (read == 0)
                return i != 0;

            if (read != id[i])
                return false;
        }

        return true;
    }

    @NotNull
    private static String trimArrayToNullTerminator(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++)
            if (bytes[i] == 0)
                return new String(bytes, 0, i);

        return new String(bytes);
    }

    boolean hasChunk(@NotNull String streamId, int index) throws IOException {
        if (streamId.length() > 40) throw new IllegalArgumentException();
        else if (index < 0 || index > MAX_CHUNKS) throw new IllegalArgumentException();

        Entry entry = find(streamId);
        if (entry == null) throw new JournalException("Couldn't find entry on journal: " + streamId);

        synchronized (io) {
            return entry.hasChunk(index);
        }
    }

    void setChunk(@NotNull String streamId, int index, boolean val) throws IOException {
        if (streamId.length() > 40) throw new IllegalArgumentException();
        else if (index < 0 || index > MAX_CHUNKS) throw new IllegalArgumentException();

        Entry entry = find(streamId);
        if (entry == null) throw new JournalException("Couldn't find entry on journal: " + streamId);

        synchronized (io) {
            entry.setChunk(index, val);
        }
    }

    @NotNull
    List<JournalHeader> getHeaders(@NotNull String streamId) throws IOException {
        if (streamId.length() > 40) throw new IllegalArgumentException();

        Entry entry = find(streamId);
        if (entry == null) throw new JournalException("Couldn't find entry on journal: " + streamId);

        synchronized (io) {
            return entry.getHeaders();
        }
    }

    @Nullable
    JournalHeader getHeader(@NotNull String streamId, byte id) throws IOException {
        if (streamId.length() > 40) throw new IllegalArgumentException();

        Entry entry = find(streamId);
        if (entry == null) throw new JournalException("Couldn't find entry on journal: " + streamId);

        synchronized (io) {
            return entry.getHeader(id);
        }
    }

    void setHeader(@NotNull String streamId, byte headerId, byte[] value) throws IOException {
        String strValue = Utils.bytesToHex(value);

        if (streamId.length() > 40) throw new IllegalArgumentException();
        else if (strValue.length() > MAX_HEADER_LENGTH) throw new IllegalArgumentException();
        else if (headerId == 0) throw new IllegalArgumentException();

        Entry entry = find(streamId);
        if (entry == null) throw new JournalException("Couldn't find entry on journal: " + streamId);

        synchronized (io) {
            entry.setHeader(headerId, strValue);
        }
    }

    void remove(@NotNull String streamId) throws IOException {
        if (streamId.length() > 40) throw new IllegalArgumentException();

        Entry entry = find(streamId);
        if (entry == null) return;

        entry.remove();
        entries.remove(streamId);
    }

    @Nullable
    private Entry find(@NotNull String id) throws IOException {
        if (id.length() > 40) throw new IllegalArgumentException();

        Entry entry = entries.get(id);
        if (entry != null) return entry;

        byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
        synchronized (io) {
            io.seek(0);

            int i = 0;
            while (true) {
                io.seek(i * JOURNAL_ENTRY_SIZE);

                int first = io.read();
                if (first == -1) // EOF
                    return null;

                if (first == 0) // Empty spot
                    continue;

                if (checkId(io, first, idBytes)) {
                    entry = new Entry(id, i * JOURNAL_ENTRY_SIZE);
                    entries.put(id, entry);
                    return entry;
                }

                i++;
            }
        }
    }

    void createIfNeeded(@NotNull String id) throws IOException {
        if (find(id) != null) return;

        if (id.length() > 40) throw new IllegalArgumentException();

        synchronized (io) {
            io.seek(0);

            int i = 0;
            while (true) {
                io.seek(i * JOURNAL_ENTRY_SIZE);

                int first = io.read();
                if (first == 0 || first == -1) { // First empty spot or EOF
                    Entry entry = new Entry(id, i * JOURNAL_ENTRY_SIZE);
                    entry.writeId();
                    entries.put(id, entry);
                    return;
                }

                i++;
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (io) {
            io.close();
        }
    }

    private static class JournalException extends IOException {
        JournalException(String message) {
            super(message);
        }
    }

    private class Entry {
        private final String id;
        private final int offset;

        private Entry(@NotNull String id, int offset) {
            this.id = id;
            this.offset = offset;
        }

        void writeId() throws IOException {
            io.seek(offset);
            io.write(id.getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < JOURNAL_ENTRY_SIZE - id.length(); i++)
                io.write(0);
        }

        void remove() throws IOException {
            io.seek(offset);
            io.write(0);
        }

        private int findHeader(byte headerId) throws IOException {
            for (int i = 0; i < MAX_HEADERS; i++) {
                io.seek(offset + MAX_ID_LENGTH + MAX_CHUNKS_SIZE + i * (MAX_HEADER_LENGTH + 1));
                if (io.read() == headerId)
                    return i;
            }

            return -1;
        }

        void setHeader(byte id, @NotNull String value) throws IOException {
            int index = findHeader(id);
            if (index == -1) {
                for (int i = 0; i < MAX_HEADERS; i++) {
                    io.seek(offset + MAX_ID_LENGTH + MAX_CHUNKS_SIZE + i * (MAX_HEADER_LENGTH + 1));
                    if (io.read() == 0) {
                        index = i;
                        break;
                    }
                }

                if (index == -1) throw new IllegalStateException();
            }

            io.seek(offset + MAX_ID_LENGTH + MAX_CHUNKS_SIZE + index * (MAX_HEADER_LENGTH + 1));
            io.write(id);
            io.write(value.getBytes());
        }

        @NotNull
        List<JournalHeader> getHeaders() throws IOException {
            List<JournalHeader> list = new ArrayList<>(MAX_HEADERS);
            for (int i = 0; i < MAX_HEADERS; i++) {
                io.seek(offset + MAX_ID_LENGTH + MAX_CHUNKS_SIZE + i * (MAX_HEADER_LENGTH + 1));
                int headerId;
                if ((headerId = io.read()) == 0)
                    continue;

                byte[] read = new byte[MAX_HEADER_LENGTH];
                io.read(read);

                list.add(new JournalHeader((byte) headerId, trimArrayToNullTerminator(read)));
            }

            return list;
        }

        @Nullable
        JournalHeader getHeader(byte id) throws IOException {
            int index = findHeader(id);
            if (index == -1) return null;

            io.seek(offset + MAX_ID_LENGTH + MAX_CHUNKS_SIZE + index * (MAX_HEADER_LENGTH + 1) + 1);
            byte[] read = new byte[MAX_HEADER_LENGTH];
            io.read(read);

            return new JournalHeader(id, trimArrayToNullTerminator(read));
        }

        void setChunk(int index, boolean val) throws IOException {
            int pos = offset + MAX_ID_LENGTH + (index / 8);
            io.seek(pos);
            int read = io.read();
            if (val) read |= (1 << (index % 8));
            else read &= ~(1 << (index % 8));
            io.seek(pos);
            io.write(read);
        }

        boolean hasChunk(int index) throws IOException {
            io.seek(offset + MAX_ID_LENGTH + (index / 8));
            return ((io.read() >>> (index % 8)) & 0b00000001) == 1;
        }
    }
}
