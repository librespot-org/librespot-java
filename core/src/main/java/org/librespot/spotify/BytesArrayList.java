package org.librespot.spotify;

import java.util.Arrays;

/**
 * @author Gianlu
 */
public class BytesArrayList {
    private byte[][] elementData;
    private int size;

    public BytesArrayList() {
        size = 0;
        elementData = new byte[5][];
    }

    private void ensureExplicitCapacity(int minCapacity) {
        if (minCapacity - elementData.length > 0)
            grow(minCapacity);
    }

    public void add(byte[] e) {
        ensureExplicitCapacity(size + 1);
        elementData[size++] = e;
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
}
