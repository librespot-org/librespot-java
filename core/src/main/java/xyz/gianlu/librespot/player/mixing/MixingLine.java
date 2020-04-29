package xyz.gianlu.librespot.player.mixing;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.codecs.Codec;

import javax.sound.sampled.AudioFormat;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Gianlu
 */
public final class MixingLine extends InputStream {
    private static final Logger LOGGER = LogManager.getLogger(MixingLine.class);
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
    public synchronized int read(@NotNull byte[] b, int off, int len) {
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
            fout = new FirstOutputStream();
        }

        return fout;
    }

    @NotNull
    public MixingOutput secondOut() {
        if (sout == null) {
            scb = new GainAwareCircularBuffer(Codec.BUFFER_SIZE * 4);
            sout = new SecondOutputStream();
        }

        return sout;
    }

    public void setGlobalGain(float gain) {
        gg = gain;
    }

    public abstract static class MixingOutput extends OutputStream {
        @Override
        public final void write(int b) {
            throw new UnsupportedOperationException();
        }

        public abstract void toggle(boolean enabled);

        public abstract void gain(float gain);

        public abstract void clear();

        public abstract void emptyBuffer();

        @NotNull
        public abstract MixingOutput stream();
    }

    public class FirstOutputStream extends MixingOutput {

        @Override
        public void write(@NotNull byte[] b, int off, int len) {
            if (fout == null || fout != this) return;
            fcb.write(b, off, len);
        }

        @Override
        public void toggle(boolean enabled) {
            if (enabled == fe) return;
            if (enabled && (fout == null || fout != this)) throw new IllegalStateException();
            fe = enabled;
            LOGGER.trace("Toggle first channel: " + enabled);
        }

        @Override
        public void gain(float gain) {
            if (fout == null || fout != this) throw new IllegalStateException();
            fg = gain;
        }

        @Override
        @SuppressWarnings("DuplicatedCode")
        public void clear() {
            if (fout == null || fout != this) throw new IllegalStateException();

            fg = 1;
            fe = false;

            CircularBuffer tmp = fcb;
            synchronized (MixingLine.this) {
                fout = null;
                fcb = null;
            }

            tmp.close();
        }

        @Override
        public @NotNull MixingOutput stream() {
            if (fout == null || fout != this) throw new IllegalStateException();
            return this;
        }

        @Override
        public void emptyBuffer() {
            if (fout == null || fout != this) throw new IllegalStateException();
            fcb.empty();
        }
    }

    public class SecondOutputStream extends MixingOutput {

        @Override
        public void write(@NotNull byte[] b, int off, int len) {
            if (sout == null || sout != this) return;
            scb.write(b, off, len);
        }

        @Override
        public void toggle(boolean enabled) {
            if (enabled == se) return;
            if (enabled && (sout == null || sout != this)) throw new IllegalStateException();
            se = enabled;
            LOGGER.trace("Toggle second channel: " + enabled);
        }

        @Override
        public void gain(float gain) {
            if (sout == null || sout != this) throw new IllegalStateException();
            sg = gain;
        }

        @Override
        @SuppressWarnings("DuplicatedCode")
        public void clear() {
            if (sout == null || sout != this) throw new IllegalStateException();

            sg = 1;
            se = false;

            CircularBuffer tmp = scb;
            synchronized (MixingLine.this) {
                sout = null;
                scb = null;
            }

            tmp.close();
        }

        @Override
        public @NotNull MixingOutput stream() {
            if (sout == null || sout != this) throw new IllegalStateException();
            return this;
        }

        @Override
        public void emptyBuffer() {
            if (sout == null || sout != this) throw new IllegalStateException();
            scb.empty();
        }
    }
}
