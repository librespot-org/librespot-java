package xyz.gianlu.librespot.player.mixing;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.player.codecs.Codec;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Gianlu
 */
public class MixingLine extends InputStream {
    private static final Logger LOGGER = Logger.getLogger(MixingLine.class);
    private final AudioFormat format;
    private CircularBuffer fcb;
    private CircularBuffer scb;
    private FirstOutputStream fout;
    private SecondOutputStream sout;
    private volatile boolean fe = false;
    private volatile boolean se = false;
    private volatile float fg = 1;
    private volatile float sg = 1;
    private float gg = 1;

    public MixingLine(@NotNull AudioFormat format) {
        this.format = format;

        if (format.getSampleSizeInBits() != 16)
            throw new IllegalArgumentException();
    }

    private static void applyGain(float gg, short val, byte[] b, int dest) {
        if (gg != 1) {
            val *= gg;
            if (val < 0) val |= 32768;
        }

        b[dest] = (byte) val;
        b[dest + 1] = (byte) (val >>> 8);
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
    public synchronized int read(@NotNull byte[] b, int off, int len) {
        int dest = off;
        for (int i = 0; i < len; i += 2, dest += 2) {
            if (fe && fcb != null && se && scb != null) {
                float first = fcb.readShort();
                first *= fg;

                float second = scb.readShort();
                first *= sg;

                int result = (int) (first + second);
                result *= gg;

                if (result > 32767) result = 32767;
                else if (result < -32768) result = -32768;
                else if (result < 0) result |= 32768;

                b[dest] = (byte) result;
                b[dest + 1] = (byte) (result >>> 8);
            } else if (fe && fcb != null) {
                applyGain(gg, fcb.readShort(), b, dest);
            } else if (se && scb != null) {
                applyGain(gg, scb.readShort(), b, dest);
            } else {
                dest -= (dest - off) % format.getFrameSize();
                break;
            }
        }

        return dest - off;
    }

    @NotNull
    public MixingOutput firstOut() {
        if (fout == null) {
            fcb = new CircularBuffer(Codec.BUFFER_SIZE * 4);
            fout = new FirstOutputStream(fcb);
        }

        return fout;
    }

    @NotNull
    public MixingOutput secondOut() {
        if (sout == null) {
            scb = new CircularBuffer(Codec.BUFFER_SIZE * 4);
            sout = new SecondOutputStream(scb);
        }

        return sout;
    }

    public void setGlobalGain(float gain) {
        gg = gain;
    }

    public interface MixingOutput {
        void toggle(boolean enabled);

        void gain(float gain);

        void write(byte[] buffer, int off, int len) throws IOException;

        void clear() throws IOException;

        @NotNull
        OutputStream stream();
    }

    private static abstract class LowLevelStream extends OutputStream {
        private final CircularBuffer buffer;

        LowLevelStream(@NotNull CircularBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public final void write(int b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public final void write(@NotNull byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }
    }

    public class FirstOutputStream extends LowLevelStream implements MixingOutput {
        FirstOutputStream(@NotNull CircularBuffer buffer) {
            super(buffer);
        }

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
        public void clear() {
            if (fout != this) throw new IllegalArgumentException();

            fg = 1;
            fe = false;

            fcb.close();

            synchronized (MixingLine.this) {
                fout = null;
                fcb = null;
            }
        }

        @Override
        public @NotNull OutputStream stream() {
            if (fout != this) throw new IllegalArgumentException();
            return this;
        }
    }

    public class SecondOutputStream extends LowLevelStream implements MixingOutput {

        SecondOutputStream(@NotNull CircularBuffer scb) {
            super(scb);
        }

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
        public void clear() {
            if (sout != this) throw new IllegalArgumentException();

            sg = 1;
            se = false;

            scb.close();

            synchronized (MixingLine.this) {
                sout = null;
                scb = null;
            }
        }

        @Override
        public @NotNull OutputStream stream() {
            if (sout != this) throw new IllegalArgumentException();
            return this;
        }
    }
}
