package xyz.gianlu.librespot;

import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * @author Gianlu
 */
public class BytesArrayList implements Iterable<byte[]> {
    private byte[][] elementData;
    private int size;

    public BytesArrayList() {
        size = 0;
        elementData = new byte[5][];
    }

    private BytesArrayList(byte[][] buffer) {
        elementData = buffer;
        size = buffer.length;
    }

    @NotNull
    public static InputStream streamBase64(@NotNull String[] payloads) {
        byte[][] decoded = new byte[payloads.length][];
        for (int i = 0; i < decoded.length; i++) decoded[i] = xyz.gianlu.librespot.common.Base64.decode(payloads[i], xyz.gianlu.librespot.common.Base64.DEFAULT);
        return new BytesArrayList(decoded).stream();
    }

    @NotNull
    public static InputStream stream(@NotNull String[] payloads) {
        byte[][] bytes = new byte[payloads.length][];
        for (int i = 0; i < bytes.length; i++) bytes[i] = payloads[i].getBytes();
        return new BytesArrayList(bytes).stream();
    }

    private void ensureExplicitCapacity(int minCapacity) {
        if (minCapacity - elementData.length > 0)
            grow(minCapacity);
    }

    public void add(byte[] e) {
        ensureExplicitCapacity(size + 1);
        elementData[size++] = e;
    }

    public byte[] get(int index) {
        if (index >= size) throw new IndexOutOfBoundsException(String.format("size: %d, index: %d", size, index));
        return elementData[index];
    }

    public byte[][] toArray() {
        return Arrays.copyOfRange(elementData, 0, size);
    }

    private void grow(int minCapacity) {
        int oldCapacity = elementData.length;
        int newCapacity = oldCapacity + (oldCapacity >> 1);
        if (newCapacity - minCapacity < 0) newCapacity = minCapacity;
        elementData = Arrays.copyOf(elementData, newCapacity);
    }

    @NotNull
    public BytesArrayList copyOfRange(int from, int to) {
        return new BytesArrayList(Arrays.copyOfRange(elementData, from, to));
    }

    public int size() {
        return size;
    }

    @NotNull
    @Override
    public Iterator<byte[]> iterator() {
        return new Itr();
    }

    @Override
    public String toString() {
        return toHex();
    }

    @NotNull
    public String toHex() {
        String[] array = new String[size()];
        byte[][] copy = toArray();
        for (int i = 0; i < copy.length; i++) array[i] = Utils.bytesToHex(copy[i]);
        return Arrays.toString(array);
    }

    @NotNull
    public InputStream stream() {
        return new InternalStream();
    }

    @NotNull
    public String readIntoString(int index) {
        byte[] b = get(index);
        return new String(b);
    }

    private class InternalStream extends InputStream {
        private int offset = 0;
        private int sub = 0;

        private InternalStream() {
        }

        @Override
        public int read(@NotNull byte[] b, int off, int len) {
            if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            } else if (len == 0) {
                return 0;
            }

            if (sub >= elementData.length)
                return -1;

            int i = 0;
            while (true) {
                int copy = Math.min(len - i, elementData[sub].length - offset);
                System.arraycopy(elementData[sub], offset, b, off + i, copy);
                i += copy;
                offset += copy;

                if (i == len)
                    return i;

                if (offset >= elementData[sub].length) {
                    offset = 0;
                    if (++sub >= elementData.length)
                        return i == 0 ? -1 : i;
                }
            }
        }

        @Override
        public synchronized int read() {
            if (sub >= elementData.length)
                return -1;

            if (offset >= elementData[sub].length) {
                offset = 0;
                if (++sub >= elementData.length)
                    return -1;
            }

            return elementData[sub][offset++] & 0xff;
        }
    }

    private class Itr implements Iterator<byte[]> {
        int cursor = 0;

        @Override
        public boolean hasNext() {
            return cursor != size();
        }

        @Override
        public byte[] next() {
            try {
                int i = cursor;
                byte[] next = get(i);
                cursor = i + 1;
                return next;
            } catch (IndexOutOfBoundsException ex) {
                throw new NoSuchElementException();
            }
        }
    }
}
