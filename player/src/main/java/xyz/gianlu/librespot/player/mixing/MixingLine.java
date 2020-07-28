package xyz.gianlu.librespot.player.mixing;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.codecs.StreamConverter;

import javax.sound.sampled.AudioFormat;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Gianlu
 */
public final class MixingLine extends InputStream {
    private static final Logger LOGGER = LogManager.getLogger(MixingLine.class);
    boolean switchFormat = false;
    private GainAwareCircularBuffer fcb;
    private GainAwareCircularBuffer scb;
    private FirstOutputStream fout;
    private SecondOutputStream sout;
    private volatile boolean fe = false;
    private volatile boolean se = false;
    private volatile float fg = 1;
    private volatile float sg = 1;
    private volatile float gg = 1;
    private AudioFormat format = AudioSink.DEFAULT_FORMAT;

    public MixingLine() {
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
            if (format != null) willRead -= willRead % format.getFrameSize();

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

    @Nullable
    public AudioFormat getFormat() {
        return format;
    }

    @Nullable
    private StreamConverter setFormat(@NotNull AudioFormat format, @NotNull MixingOutput from) {
        if (this.format == null) {
            this.format = format;
            return null;
        } else if (!this.format.matches(format)) {
            if (StreamConverter.canConvert(format, this.format)) {
                LOGGER.info("Converting, '{}' -> '{}'", format, this.format);
                return StreamConverter.converter(format, this.format);
            } else {
                if (fout == from && sout != null) sout.clear();
                else if (sout == from && fout != null) fout.clear();

                LOGGER.info("Switching format, '{}' -> '{}'", this.format, format);
                this.format = format;
                switchFormat = true;
                return null;
            }
        } else {
            return null;
        }
    }

    public abstract static class MixingOutput extends OutputStream {
        StreamConverter converter = null;

        @Override
        public final void write(int b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public final void write(@NotNull byte[] b, int off, int len) {
            if (converter != null) {
                converter.write(b, off, len);
                writeBuffer(converter.convert());
            } else {
                writeBuffer(b, off, len);
            }
        }

        protected void writeBuffer(byte[] b) {
            writeBuffer(b, 0, b.length);
        }

        protected abstract void writeBuffer(@NotNull byte[] b, int off, int len);

        public abstract void toggle(boolean enabled, @Nullable AudioFormat format);

        public abstract void gain(float gain);

        public abstract void clear();

        public abstract void emptyBuffer();
    }

    public class FirstOutputStream extends MixingOutput {

        @Override
        public void writeBuffer(@NotNull byte[] b, int off, int len) {
            if (fout == null || fout != this) return;
            fcb.write(b, off, len);
        }

        @Override
        @SuppressWarnings("DuplicatedCode")
        public void toggle(boolean enabled, @Nullable AudioFormat format) {
            if (enabled == fe) return;
            if (enabled && (fout == null || fout != this)) return;
            if (enabled && format == null) throw new IllegalArgumentException();

            if (format != null) converter = setFormat(format, this);
            fe = enabled;
            LOGGER.trace("Toggle first channel: " + enabled);
        }

        @Override
        public void gain(float gain) {
            if (fout == null || fout != this) return;
            fg = gain;
        }

        @Override
        public void clear() {
            if (fout == null || fout != this) return;

            fg = 1;
            fe = false;

            fcb.close();
            synchronized (MixingLine.this) {
                fout = null;
                fcb = null;
            }
        }

        @Override
        public void emptyBuffer() {
            if (fout == null || fout != this) return;
            fcb.empty();
        }
    }

    public class SecondOutputStream extends MixingOutput {

        @Override
        public void writeBuffer(@NotNull byte[] b, int off, int len) {
            if (sout == null || sout != this) return;
            scb.write(b, off, len);
        }

        @Override
        @SuppressWarnings("DuplicatedCode")
        public void toggle(boolean enabled, @Nullable AudioFormat format) {
            if (enabled == se) return;
            if (enabled && (sout == null || sout != this)) return;
            if (enabled && format == null) throw new IllegalArgumentException();

            if (format != null) converter = setFormat(format, this);
            se = enabled;
            LOGGER.trace("Toggle second channel: " + enabled);
        }

        @Override
        public void gain(float gain) {
            if (sout == null || sout != this) return;
            sg = gain;
        }

        @Override
        public void clear() {
            if (sout == null || sout != this) return;

            sg = 1;
            se = false;

            scb.close();
            synchronized (MixingLine.this) {
                sout = null;
                scb = null;
            }
        }

        @Override
        public void emptyBuffer() {
            if (sout == null || sout != this) return;
            scb.empty();
        }
    }
}
