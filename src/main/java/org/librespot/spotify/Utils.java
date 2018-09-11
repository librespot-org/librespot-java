package org.librespot.spotify;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * @author Gianlu
 */
public class Utils {
    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    @NotNull
    public static String[] split(@NotNull String str, char c) {
        if (str.isEmpty()) return new String[0];

        int size = 1;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) size++;
        }

        String tmp = str;
        String[] split = new String[size];
        for (int j = size - 1; j >= 0; j--) {
            int i = tmp.lastIndexOf(c);
            if (i == -1) {
                split[j] = tmp;
            } else {
                split[j] = tmp.substring(i + 1, tmp.length());
                tmp = tmp.substring(0, i);
            }
        }

        return split;
    }

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

    @NotNull
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }

        return new String(hexChars);
    }
}
