package xyz.gianlu.librespot.player.mixing;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.codecs.Codec;

import javax.sound.sampled.AudioFormat;
import java.io.*;

/**
 * @author Gianlu
 */
public class MixingLine extends InputStream {
    private static final Logger LOGGER = Logger.getLogger(MixingLine.class);
    private final byte[] mb = new byte[2];
    private final Object readLock = new Object();
    private final AudioFormat format;
    private PipedInputStream fin;
    private PipedInputStream sin;
    private FirstOutputStream fout;
    private SecondOutputStream sout;
    private volatile boolean fe = false;
    private volatile boolean se = false;
    private volatile float fg = 1;
    private volatile float sg = 1;

    public MixingLine(@NotNull AudioFormat format) {
        this.format = format;
    }

    public final int getFrameSize() {
        return format.getFrameSize();
    }

    @Override
    public int read() {
        throw new UnsupportedOperationException();
    }

    @Override
    @SuppressWarnings("DuplicatedCode")
    public int read(@NotNull byte[] b, int off, int len) throws IOException {
        int dest = off;
        for (int i = 0; i < len; i += 2, dest += 2) {
            if (fe && fin != null && se && sin != null) {
                float first;
                synchronized (readLock) {
                    if (fin.read(mb) != 2) {
                        first = 0;
                    } else {
                        first = (short) ((mb[0] & 0xFF) | ((mb[1] & 0xFF) << 8));
                        first = (short) (first * fg);
                    }
                }

                float second;
                synchronized (readLock) {
                    if (sin.read(mb) != 2) {
                        second = 0;
                    } else {
                        second = (short) ((mb[0] & 0xFF) | ((mb[1] & 0xFF) << 8));
                        second = (short) (second * sg);
                    }
                }

                int result = (int) (first + second);
                if (result > 32767) result = 32767;
                else if (result < -32768) result = -32768;
                else if (result < 0) result |= 32768;

                b[dest] = (byte) result;
                b[dest + 1] = (byte) (result >>> 8);
            } else if (fe && fin != null) {
                synchronized (readLock) {
                    if (fin.read(mb) != 2) {
                        b[dest] = 0;
                        b[dest + 1] = 0;
                    } else {
                        b[dest] = mb[0];
                        b[dest + 1] = mb[1];
                    }
                }
            } else if (se && sin != null) {
                synchronized (readLock) {
                    if (sin.read(mb) != 2) {
                        b[dest] = 0;
                        b[dest + 1] = 0;
                    } else {
                        b[dest] = mb[0];
                        b[dest + 1] = mb[1];
                    }
                }
            } else {
                dest -= (dest - off) % format.getFrameSize(); // Drops at MAX 3 frames
                break;
            }
        }

        return dest - off;
    }

    @NotNull
    public MixingOutput firstOut() {
        if (fout == null) {
            try {
                fin = new PipedInputStream(Codec.BUFFER_SIZE * 2);
                fin.connect(fout = new FirstOutputStream());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        return fout;
    }

    @NotNull
    public MixingOutput secondOut() {
        if (sout == null) {
            try {
                sin = new PipedInputStream(Codec.BUFFER_SIZE * 2);
                sin.connect(sout = new SecondOutputStream());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        return sout;
    }

    public interface MixingOutput {
        void toggle(boolean enabled);

        void gain(float gain);

        void write(byte[] buffer, int off, int len) throws IOException;

        void clear() throws IOException;

        @NotNull
        OutputStream stream();
    }

    public class FirstOutputStream extends PipedOutputStream implements MixingOutput {

        @Override
        public void toggle(boolean enabled) {
            if (enabled == fe) return;
            if (enabled && fout != this) throw new IllegalArgumentException();
            fe = enabled;
            LOGGER.trace("Toggle first channel: " + enabled);
        }

        @Override
        public void gain(float gain) {
            if (fout != this) throw new IllegalArgumentException();
            fg = gain;
        }

        @Override
        @SuppressWarnings("DuplicatedCode")
        public void clear() throws IOException {
            if (fout != this) throw new IllegalArgumentException();

            fg = 1;
            fe = false;

            fout.flush();
            fout.close();

            PipedInputStream tmp = fin;
            synchronized (readLock) {
                fout = null;
                fin = null;
            }

            tmp.close();
        }

        @Override
        public @NotNull OutputStream stream() {
            if (fout != this) throw new IllegalArgumentException();
            return this;
        }
    }

    public class SecondOutputStream extends PipedOutputStream implements MixingOutput {

        @Override
        public void toggle(boolean enabled) {
            if (enabled == se) return;
            if (enabled && sout != this) throw new IllegalArgumentException();
            se = enabled;
            LOGGER.trace("Toggle second channel: " + enabled);
        }

        @Override
        public void gain(float gain) {
            if (sout != this) throw new IllegalArgumentException();
            sg = gain;
        }

        @Override
        @SuppressWarnings("DuplicatedCode")
        public void clear() throws IOException {
            if (sout != this) throw new IllegalArgumentException();

            sg = 1;
            se = false;

            sout.flush();
            sout.close();

            PipedInputStream tmp = sin;
            synchronized (readLock) {
                sout = null;
                sin = null;
            }

            tmp.close();
        }

        @Override
        public @NotNull OutputStream stream() {
            if (sout != this) throw new IllegalArgumentException();
            return this;
        }
    }
}
