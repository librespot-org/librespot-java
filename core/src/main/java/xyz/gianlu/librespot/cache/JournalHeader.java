package xyz.gianlu.librespot.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;

import java.util.List;

/**
 * @author Gianlu
 */
public final class JournalHeader {
    public final byte id;
    public final byte[] value;

    JournalHeader(byte id, @NotNull String value) {
        this.id = id;
        this.value = Utils.hexToBytes(value);
    }

    @Nullable
    public static JournalHeader find(List<JournalHeader> headers, byte id) {
        for (JournalHeader header : headers)
            if (header.id == id)
                return header;

        return null;
    }

    @Override
    public String toString() {
        return "JournalHeader{" + "id=" + id + ", value=" + Utils.bytesToHex(value) + '}';
    }
}
