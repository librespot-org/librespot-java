package xyz.gianlu.librespot.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Gianlu
 */
class CacheJournal implements Closeable {
    private static final int MAX_CHUNKS = 2048;
    private static final int MAX_HEADERS = 16;
    private static final int MAX_HEADER_LENGTH = 1023;
    private static final int MAX_ID_LENGTH = 40;
    private static final int JOURNAL_ENTRY_SIZE = MAX_ID_LENGTH + MAX_CHUNKS + (1 + MAX_HEADER_LENGTH) * MAX_HEADERS;
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
        if (streamId.length() > 40) throw new IllegalArgumentException();
        else if (value.length > MAX_HEADER_LENGTH) throw new IllegalArgumentException();

        Entry entry = find(streamId);
        if (entry == null) throw new JournalException("Couldn't find entry on journal: " + streamId);

        synchronized (io) {
            entry.setHeader(headerId, value);
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

    void create(@NotNull String id) throws IOException {
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
        }

        void remove() throws IOException {
            io.seek(offset);
            io.write(0);
        }

        void setHeader(byte headerId, @NotNull byte[] value) throws IOException {
            // TODO
        }

        @NotNull
        List<JournalHeader> getHeaders() throws IOException {
            return Collections.emptyList(); // TODO
        }

        @Nullable
        JournalHeader getHeader(byte id) throws IOException {
            return null; // TODO
        }

        void setChunk(int index, boolean val) throws IOException {
            // TODO
        }

        boolean hasChunk(int index) throws IOException {
            return false; // TODO
        }
    }
}
