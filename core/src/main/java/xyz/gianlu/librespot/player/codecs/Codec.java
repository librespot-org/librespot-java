package xyz.gianlu.librespot.player.codecs;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.GeneralAudioStream;
import xyz.gianlu.librespot.player.NormalizationData;
import xyz.gianlu.librespot.player.Player;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Gianlu
 */
public abstract class Codec {
    public static final int BUFFER_SIZE = 2048;
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

    public abstract int readSome(@NotNull OutputStream out) throws IOException, CodecException;

    /**
     * @return Time in millis
     * @throws CannotGetTimeException If the codec can't determine the time
     */
    public abstract int time() throws CannotGetTimeException;

    public final int remaining() throws CannotGetTimeException {
        return duration - time();
    }

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

    public static class CannotGetTimeException extends Exception {
        CannotGetTimeException() {
        }
    }

    public static class CodecException extends Exception {
        CodecException() {
        }
    }
}
