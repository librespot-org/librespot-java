package xyz.gianlu.librespot.player.codecs;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.*;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * @author Gianlu
 */
public abstract class Codec implements Runnable {
    protected static final int BUFFER_SIZE = 2048;
    private static final long TRACK_PRELOAD_THRESHOLD = TimeUnit.SECONDS.toMillis(10);
    private static final Logger LOGGER = Logger.getLogger(Codec.class);
    protected final InputStream audioIn;
    protected final float normalizationFactor;
    protected final Object pauseLock = new Object();
    protected final int duration;
    protected final PlayerRunner.Listener listener;
    protected final LinesHolder lines;
    private final boolean preloadEnabled;
    protected volatile boolean playing = false;
    protected volatile boolean stopped = false;
    protected PlayerRunner.Controller controller;
    private volatile boolean calledPreload = false;

    Codec(@NotNull GeneralAudioStream audioFile, @Nullable NormalizationData normalizationData, @NotNull Player.Configuration conf,
          @NotNull PlayerRunner.Listener listener, @NotNull LinesHolder lines, int duration) {
        this.audioIn = audioFile.stream();
        this.listener = listener;
        this.lines = lines;
        this.duration = duration;
        this.normalizationFactor = normalizationData != null ? normalizationData.getFactor(conf) : 1;
        this.preloadEnabled = conf.preloadEnabled();
    }

    public final void play() {
        playing = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    public final void stop() {
        stopped = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll(); // Allow thread to exit
        }
    }

    public final void pause() {
        playing = false;
    }

    @Nullable
    public final PlayerRunner.Controller controller() {
        return controller;
    }

    @Override
    public final void run() {
        try {
            readBody();
            if (!stopped) listener.endOfTrack();
        } catch (IOException | LineUnavailableException | CodecException ex) {
            if (!stopped) listener.playbackError(ex);
        } finally {
            cleanup();
        }
    }

    protected abstract void readBody() throws IOException, LineUnavailableException, CodecException;

    public abstract int time() throws CannotGetTimeException;

    public void cleanup() {
        try {
            audioIn.close();
        } catch (IOException ignored) {
        }
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

    protected final void checkPreload() {
        int time;
        try {
            time = time();
        } catch (CannotGetTimeException ex) {
            return;
        }

        if (preloadEnabled && !calledPreload && !stopped && duration - time <= TRACK_PRELOAD_THRESHOLD) {
            calledPreload = true;
            listener.preloadNextTrack();
        }
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
