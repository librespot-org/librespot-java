package xyz.gianlu.librespot.cache;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author Gianlu
 */
public final class JournalHeader {
    public final byte id;
    public final byte[] value;

    private JournalHeader(byte id, byte[] value) {
        this.id = id;
        this.value = value;
    }

    @Nullable
    public static JournalHeader find(List<JournalHeader> headers, byte id) {
        for (JournalHeader header : headers)
            if (header.id == id)
                return header;

        return null;
    }
}
