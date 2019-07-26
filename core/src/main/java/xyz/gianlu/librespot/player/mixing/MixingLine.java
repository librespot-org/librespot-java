package xyz.gianlu.librespot.player.mixing;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.codecs.Codec;

import java.io.*;

/**
 * @author Gianlu
 */
public class MixingLine extends InputStream {
    private static final Logger LOGGER = Logger.getLogger(MixingLine.class);
    private final PipedInputStream fin;
    private final PipedInputStream sin;
    private final PipedOutputStream fout;
    private final PipedOutputStream sout;
    private boolean fe = false;
    private boolean se = false;

    public MixingLine() {
        try {
            fin = new PipedInputStream(Codec.BUFFER_SIZE * 2);
            fin.connect(fout = new PipedOutputStream());
            sin = new PipedInputStream(Codec.BUFFER_SIZE * 2);
            sin.connect(sout = new PipedOutputStream());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public int read() {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized int read(@NotNull byte[] b, int off, int len) throws IOException {
        if (len % 2 != 0) throw new IllegalArgumentException();

        if (fe && se) {
            int dest = off;
            for (int i = 0; i < len; i += 2, dest += 2) {
                int first = (byte) fin.read();
                first |= (fin.read() << 8);

                int second = (byte) sin.read();
                second |= (sin.read() << 8);

                int result = first + second;
                if (result > 32767) result = 32767;
                else if (result < -32768) result = -32768;
                else if (result < 0) result = result | 32768;

                b[dest] = (byte) result;
                b[dest + 1] = (byte) (result >>> 8);
            }

            return len;
        } else if (fe) {
            return fin.read(b, off, len);
        } else if (se) {
            return sin.read(b, off, len);
        } else {
            return 0;
        }
    }

    public synchronized void first(boolean enabled) {
        fe = enabled;
        LOGGER.trace("Toggle first channel: " + enabled);
    }

    public synchronized void second(boolean enabled) {
        se = enabled;
        LOGGER.trace("Toggle second channel: " + enabled);
    }

    @NotNull
    public OutputStream firstOut() {
        return fout;
    }

    @NotNull
    public OutputStream secondOut() {
        return sout;
    }

    public synchronized void clearFirst() {
        try {
            fout.flush();
            fin.skip(fin.available());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public synchronized void clearSecond() {
        try {
            sout.flush();
            sin.skip(sin.available());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
