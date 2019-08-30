package xyz.gianlu.librespot.player.mixing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import xyz.gianlu.librespot.common.Utils;

import java.io.Closeable;
import java.io.IOException;

/**
 * @author Gianlu
 */
public class CircularBuffer implements Closeable {
    private final byte[] data;
    private final Object awaitSpaceLock = new Object();
    private final Object awaitDataLock = new Object();
    private int head;
    private int tail;
    private volatile boolean closed = false;
    private int awaitFreeBytes = -1;
    private int awaitDataBytes = -1;

    public CircularBuffer(int bufferSize) {
        data = new byte[bufferSize + 1];
        head = 0;
        tail = 0;
    }

    private void awaitSpace(int count) {
        if (free() >= count) return;

        checkNotifyData();

        synchronized (awaitSpaceLock) {
            awaitFreeBytes = count;

            try {
                awaitSpaceLock.wait();
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private void awaitData(int count) {
        if (available() >= count) return;

        checkNotifySpace();

        synchronized (awaitDataLock) {
            awaitDataBytes = count;

            try {
                awaitDataLock.wait();
            } catch (InterruptedException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("Buffer is closed!");

        awaitSpace(len);
        if (closed) return;

        for (int i = off; i < off + len; i++) {
            data[tail++] = b[i];
            if (tail == data.length)
                tail = 0;
        }

        checkNotifyData();
    }

    public void write(byte value) throws IOException {
        if (closed) throw new IOException("Buffer is closed!");

        awaitSpace(1);
        if (closed) return;

        data[tail++] = value;
        if (tail == data.length)
            tail = 0;

        checkNotifyData();
    }

    public short readShort() throws IOException {
        if (closed) throw new IOException("Buffer is closed!");

        awaitData(2);
        if (closed) return -1;

        short val = (short) ((readInternal() & 0xFF) | ((readInternal() & 0xFF) << 8));
        checkNotifySpace();
        return val;
    }

    private int readInternal() {
        int value = data[head++] & 0xFF;
        if (head == data.length)
            head = 0;

        return value;
    }

    /**
     * Reads a single byte. If data is not available at this moment in time, it blocks until a byte is available.
     *
     * @return a byte from the buffer.
     */
    public int read() throws IOException {
        if (closed) throw new IOException("Buffer is closed!");

        awaitData(1);
        if (closed) return -1;

        int value = readInternal();
        checkNotifySpace();
        return value;
    }

    private void checkNotifyData() {
        if (awaitDataBytes != -1 && available() >= awaitDataBytes) {
            synchronized (awaitDataLock) {
                awaitDataBytes = -1;
                awaitDataLock.notifyAll();
            }
        }
    }

    private void checkNotifySpace() {
        if (awaitFreeBytes != -1 && free() >= awaitFreeBytes) {
            synchronized (awaitSpaceLock) {
                awaitFreeBytes = -1;
                awaitSpaceLock.notifyAll();
            }
        }
    }

    /**
     * @return The number of bytes that can be read at this moment in time without blocking.
     */
    public int available() {
        if (head > tail) {
            return tail + (data.length - head);
        } else if (head < tail) {
            return tail - head;
        } else {
            return 0; // head and tail are the same, initial position only
        }
    }

    /**
     * @return The number of bytes that can be written at this moment in time without blocking.
     */
    public int free() {
        if (head > tail) {
            return head - tail - 1;
        } else if (head < tail) {
            return (data.length - 1 - tail) + head;
        } else {
            return data.length - 1; // head and tail are the same, initial position only
        }
    }

    /**
     * @return Whether the buffer is full and no data can be written without blocking.
     */
    public boolean full() {
        return tail + 1 == head || (head == 0 && tail == data.length - 1);
    }

    @Override
    public void close() {
        closed = true;

        synchronized (awaitSpaceLock) {
            awaitSpaceLock.notifyAll();
        }

        synchronized (awaitDataLock) {
            awaitDataLock.notifyAll();
        }
    }

    @TestOnly
    @NotNull
    public String dump() {
        return "CircularBuffer {head: " + head + ", tail: " + tail + ", data: " + Utils.bytesToHex(data) + "}";
    }
}