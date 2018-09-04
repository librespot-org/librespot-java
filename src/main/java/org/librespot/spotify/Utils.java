package org.librespot.spotify;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * @author Gianlu
 */
public class Utils {

    @NotNull
    public static byte[] toByteArray(@NotNull BigInteger i) {
        byte[] array = i.toByteArray();
        if (array[0] == 0) array = Arrays.copyOfRange(array, 1, array.length);
        return array;
    }

    @NotNull
    public static byte[] toByteArray(int i) {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(i);
        return buffer.array();
    }
}
