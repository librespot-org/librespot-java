package org.librespot.spotify;

import org.jetbrains.annotations.NotNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * @author Gianlu
 */
public class Utils {
    /**
     * @param integer a BigInteger
     * @return the byte array representation without the sign byte if it is 0
     */
    @NotNull
    public static byte[] toByteArray(@NotNull BigInteger integer) {
        byte[] bytes = integer.toByteArray();
        if (bytes[0] == 0) return Arrays.copyOfRange(bytes, 1, bytes.length);
        else return bytes;
    }

    public static void readChecked(InputStream in, byte[] buffer, int length) throws IOException {
        int read = in.read(buffer, 0, length);
        if (read == -1) throw new EOFException("Couldn't read " + length + " bytes due to EOF!");
        if (read != length) throw new IOException("Read only " + read + " bytes over " + length + " expected!");
    }

    public static void readChecked(InputStream in, byte[] buffer) throws IOException {
        readChecked(in, buffer, buffer.length);
    }
}
