package xyz.gianlu.librespot.player.mixing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import xyz.gianlu.librespot.common.Utils;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Gianlu
 */
public class CircularBuffer implements Closeable {
    protected final Lock lock = new ReentrantLock();
    protected final Condition awaitSpace = lock.newCondition();
    private final byte[] data;
    private final Condition awaitData = lock.newCondition();
    protected volatile boolean closed = false;
    private int head;
    private int tail;

    public CircularBuffer(int bufferSize) {
        data = new byte[bufferSize + 1];
        head = 0;
        tail = 0;
    }

    private void awaitSpace(int count) throws InterruptedException {
        while (free() < count && !closed)
            awaitSpace.await(100, TimeUnit.MILLISECONDS);
    }

    protected void awaitData(int count) throws InterruptedException {
        while (available() < count && !closed)
            awaitData.await(100, TimeUnit.MILLISECONDS);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("Buffer is closed!");

        lock.lock();

        try {
            awaitSpace(len);
            if (closed) return;

            for (int i = off; i < off + len; i++) {
                data[tail++] = b[i];
                if (tail == data.length)
                    tail = 0;
            }

            awaitData.signal();
        } catch (InterruptedException ex) {
            throw new IOException(ex);
        } finally {
            lock.unlock();
        }
    }

    @TestOnly
    public void write(byte value) throws IOException {
        if (closed) throw new IOException("Buffer is closed!");

        lock.lock();

        try {
            awaitSpace(1);
            if (closed) return;

            data[tail++] = value;
            if (tail == data.length)
                tail = 0;

            awaitData.signal();
        } catch (InterruptedException ex) {
            throw new IOException(ex);
        } finally {
            lock.unlock();
        }
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) return -1;

        lock.lock();

        try {
            awaitData(len);
            if (closed) return -1;

            int dest = off;
            for (int i = 0; i < len; i += 2, dest += 2) {
                b[dest] = (byte) readInternal();
                b[dest + 1] = (byte) readInternal();
            }

            awaitSpace.signal();
            return dest - off;
        } catch (InterruptedException ex) {
            throw new IOException(ex);
        } finally {
            lock.unlock();
        }
    }

    protected int readInternal() {
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
        if (closed) return -1;

        lock.lock();

        try {
            awaitData(1);
            if (closed) return -1;

            int value = readInternal();
            awaitSpace.signal();
            return value;
        } catch (InterruptedException ex) {
            throw new IOException(ex);
        } finally {
            lock.unlock();
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

        lock.lock();

        try {
            awaitSpace.signalAll();
            awaitData.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @TestOnly
    @NotNull
    public String dump() {
        return "CircularBuffer {head: " + head + ", tail: " + tail + ", data: " + Utils.bytesToHex(data) + "}";
    }
}