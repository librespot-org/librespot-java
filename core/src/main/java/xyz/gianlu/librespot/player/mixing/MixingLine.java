package xyz.gianlu.librespot.player.mixing;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.codecs.Codec;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Gianlu
 */
public final class MixingLine extends InputStream {
    private static final Logger LOGGER = Logger.getLogger(MixingLine.class);
    private final AudioFormat format;
    private GainAwareCircularBuffer fcb;
    private GainAwareCircularBuffer scb;
    private FirstOutputStream fout;
    private SecondOutputStream sout;
    private volatile boolean fe = false;
    private volatile boolean se = false;
    private volatile float fg = 1;
    private volatile float sg = 1;
    private volatile float gg = 1;

    public MixingLine(@NotNull AudioFormat format) {
        this.format = format;

        if (format.getSampleSizeInBits() != 16)
            throw new IllegalArgumentException();
    }

    @Override
    public int read() {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized int read(@NotNull byte[] b, int off, int len) throws IOException {
        if (fe && fcb != null && se && scb != null) {
            int willRead = Math.min(fcb.available(), scb.available());
            willRead = Math.min(willRead, len);
            willRead -= willRead % format.getFrameSize();

            fcb.read(b, off, willRead);
            scb.readMergeGain(b, off, willRead, gg, fg, sg);
            return willRead;
        } else if (fe && fcb != null) {
            fcb.readGain(b, off, len, gg * fg);
            return len;
        } else if (se && scb != null) {
            scb.readGain(b, off, len, gg * sg);
            return len;
        } else {
            return 0;
        }
    }

    @Nullable
    public MixingOutput someOut() {
        if (fout == null) return firstOut();
        else if (sout == null) return secondOut();
        else return null;
    }

    @NotNull
    public MixingOutput firstOut() {
        if (fout == null) {
            fcb = new GainAwareCircularBuffer(Codec.BUFFER_SIZE * 4);
            fout = new FirstOutputStream(fcb);
        }

        return fout;
    }

    @NotNull
    public MixingOutput secondOut() {
        if (sout == null) {
            scb = new GainAwareCircularBuffer(Codec.BUFFER_SIZE * 4);
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

        void clear();

        @NotNull
        LowLevelStream stream();
    }

    public static abstract class LowLevelStream extends OutputStream {
        private final CircularBuffer buffer;

        LowLevelStream(@NotNull CircularBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public final void write(int b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public final void write(@NotNull byte[] b, int off, int len) throws IOException {
            buffer.write(b, off, len);
        }

        public final void emptyBuffer() {
            buffer.empty();
        }
    }

    public class FirstOutputStream extends LowLevelStream implements MixingOutput {
        FirstOutputStream(@NotNull CircularBuffer buffer) {
            super(buffer);
        }

        @Override
        public void toggle(boolean enabled) {
            if (enabled == fe) return;
            if (enabled && fout != null && fout != this) throw new IllegalArgumentException();
            fe = enabled;
            LOGGER.trace("Toggle first channel: " + enabled);
        }

        @Override
        public void gain(float gain) {
            if (fout != null && fout != this) throw new IllegalArgumentException();
            fg = gain;
        }

        @Override
        @SuppressWarnings("DuplicatedCode")
        public void clear() {
            if (fout != null && fout != this) throw new IllegalArgumentException();

            fg = 1;
            fe = false;

            fcb.close();

            synchronized (MixingLine.this) {
                fout = null;
                fcb = null;
            }
        }

        @Override
        public @NotNull LowLevelStream stream() {
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
            if (enabled && sout != null && sout != this) throw new IllegalArgumentException();
            se = enabled;
            LOGGER.trace("Toggle second channel: " + enabled);
        }

        @Override
        public void gain(float gain) {
            if (sout != null && sout != this) throw new IllegalArgumentException();
            sg = gain;
        }

        @Override
        @SuppressWarnings("DuplicatedCode")
        public void clear() {
            if (sout != null && sout != this) throw new IllegalArgumentException();

            sg = 1;
            se = false;

            scb.close();

            synchronized (MixingLine.this) {
                sout = null;
                scb = null;
            }
        }

        @Override
        public @NotNull LowLevelStream stream() {
            if (sout != this) throw new IllegalArgumentException();
            return this;
        }
    }
}
