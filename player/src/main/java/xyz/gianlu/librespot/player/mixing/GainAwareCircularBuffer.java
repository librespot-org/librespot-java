package xyz.gianlu.librespot.player.mixing;

/**
 * @author Gianlu
 */
class GainAwareCircularBuffer extends CircularBuffer {
    GainAwareCircularBuffer(int bufferSize) {
        super(bufferSize);
    }

    private static void writeToArray(int val, byte[] b, int dest) {
        if (val > 32767) val = 32767;
        else if (val < -32768) val = -32768;
        else if (val < 0) val |= 32768;

        b[dest] = (byte) val;
        b[dest + 1] = (byte) (val >>> 8);
    }

    void readGain(byte[] b, int off, int len, float gain) {
        if (closed) return;

        lock.lock();

        try {
            awaitData(len);
            if (closed) return;

            int dest = off;
            for (int i = 0; i < len; i += 2, dest += 2) {
                int val = (short) ((readInternal() & 0xFF) | ((readInternal() & 0xFF) << 8));
                val *= gain;
                writeToArray(val, b, dest);
            }

            awaitSpace.signal();
        } catch (InterruptedException ignored) {
        } finally {
            lock.unlock();
        }
    }

    void readMergeGain(byte[] b, int off, int len, float gg, float fg, float sg) {
        if (closed) return;

        lock.lock();

        try {
            awaitData(len);
            if (closed) return;

            int dest = off;
            for (int i = 0; i < len; i += 2, dest += 2) {
                short first = (short) ((b[dest] & 0xFF) | ((b[dest + 1] & 0xFF) << 8));
                first *= fg;

                short second = (short) ((readInternal() & 0xFF) | ((readInternal() & 0xFF) << 8));
                second *= sg;

                int result = first + second;
                result *= gg;
                writeToArray(result, b, dest);
            }

            awaitSpace.signal();
        } catch (InterruptedException ignored) {
        } finally {
            lock.unlock();
        }
    }
}
