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
    private PipedInputStream fin;
    private PipedInputStream sin;
    private PipedOutputStream fout;
    private PipedOutputStream sout;
    private volatile boolean fe = false;
    private volatile boolean se = false;
    private volatile float fg = 1;
    private volatile float sg = 1;

    public MixingLine() {
        firstOut();
        secondOut();
    }

    @Override
    public int read() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int read(@NotNull byte[] b, int off, int len) throws IOException {
        if (len % 2 != 0) throw new IllegalArgumentException();

        int dest = off;
        for (int i = 0; i < len; i += 2, dest += 2) {
            if (fe && se) {
                float first = (short) ((fin.read() & 0xFF) | ((fin.read() & 0xFF) << 8));
                first = (short) (first * fg);

                float second = (short) ((sin.read() & 0xFF) | ((sin.read() & 0xFF) << 8));
                second = (short) (second * sg);

                int result = (int) (first + second);
                if (result > 32767) result = 32767;
                else if (result < -32768) result = -32768;
                else if (result < 0) result |= 32768;

                b[dest] = (byte) result;
                b[dest + 1] = (byte) (result >>> 8);
            } else if (fe) {
                b[dest] = (byte) fin.read();
                b[dest + 1] = (byte) fin.read();
            } else if (se) {
                b[dest] = (byte) sin.read();
                b[dest + 1] = (byte) sin.read();
            } else {
                break;
            }
        }

        return dest - off;
    }

    public void firstGain(float fg) {
        this.fg = fg;
    }

    public void secondGain(float sg) {
        this.sg = sg;
    }

    public void first(boolean enabled) {
        if (enabled && fout == null) throw new IllegalArgumentException();

        fe = enabled;
        LOGGER.trace("Toggle first channel: " + enabled);
    }

    public void second(boolean enabled) {
        if (enabled && sout == null) throw new IllegalArgumentException();

        se = enabled;
        LOGGER.trace("Toggle second channel: " + enabled);
    }

    @NotNull
    public OutputStream firstOut() {
        if (fout == null) {
            try {
                fin = new PipedInputStream(Codec.BUFFER_SIZE * 2);
                fin.connect(fout = new PipedOutputStream());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        return fout;
    }

    @NotNull
    public OutputStream secondOut() {
        if (sout == null) {
            try {
                sin = new PipedInputStream(Codec.BUFFER_SIZE * 2);
                sin.connect(sout = new PipedOutputStream());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        return sout;
    }

    public void clearFirst() {
        try {
            fout.flush();
            fin.skip(fin.available());
            fg = 1;
            fin = null;
            fout = null;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void clearSecond() {
        try {
            sout.flush();
            sin.skip(sin.available());
            sg = 1;
            sin = null;
            sout = null;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
