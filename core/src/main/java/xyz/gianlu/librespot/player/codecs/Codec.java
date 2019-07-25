package xyz.gianlu.librespot.player.codecs;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.GeneralAudioStream;
import xyz.gianlu.librespot.player.NormalizationData;
import xyz.gianlu.librespot.player.Player;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public abstract class Codec {
    protected static final int BUFFER_SIZE = 2048;
    private static final Logger LOGGER = Logger.getLogger(Codec.class);
    protected final InputStream audioIn;
    protected final float normalizationFactor;
    protected final int duration;
    protected AudioFormat format;

    Codec(@NotNull GeneralAudioStream audioFile, @Nullable NormalizationData normalizationData, @NotNull Player.Configuration conf, int duration) {
        this.audioIn = audioFile.stream();
        this.duration = duration;
        this.normalizationFactor = normalizationData != null ? normalizationData.getFactor(conf) : 1;
    }

    protected abstract int readSome(@NotNull WritableThing thing) throws IOException, LineUnavailableException, CodecException, InterruptedException;

    public abstract int time() throws CannotGetTimeException;

    public void cleanup() throws IOException {
        audioIn.close();
    }

    public void seek(int positionMs) {
        if (positionMs < 0) positionMs = 0;

        try {
            audioIn.reset();
            if (positionMs > 0) {
                int skip = Math.round(audioIn.available() / (float) duration * positionMs);
                if (skip > audioIn.available()) skip = audioIn.available();

                long skipped = audioIn.skip(skip);
                if (skip != skipped)
                    throw new IOException(String.format("Failed seeking, skip: %d, skipped: %d", skip, skipped));
            }
        } catch (IOException ex) {
            LOGGER.fatal("Failed seeking!", ex);
        }
    }

    @NotNull
    public final AudioFormat getAudioFormat() {
        if (format == null) throw new IllegalStateException();
        return format;
    }

    protected final void setAudioFormat(@NotNull AudioFormat format) {
        this.format = format;
    }

    public interface WritableThing {
        int write(byte[] b, int off, int len);
    }

    public static class CannotGetTimeException extends Exception {
        protected CannotGetTimeException() {
        }
    }

    public static class CodecException extends Exception {

        CodecException() {
        }

        CodecException(@NotNull Throwable ex) {
            super(ex);
        }
    }
}
