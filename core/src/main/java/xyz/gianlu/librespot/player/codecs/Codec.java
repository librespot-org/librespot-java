package xyz.gianlu.librespot.player.codecs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.player.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * @author Gianlu
 */
public abstract class Codec {
    protected static final int BUFFER_SIZE = 2048;
    private static final long TRACK_PRELOAD_THRESHOLD = TimeUnit.SECONDS.toMillis(10);
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

    public Codec(@NotNull GeneralAudioStream audioFile, @Nullable NormalizationData normalizationData, @NotNull Player.Configuration conf,
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

    public abstract void read();

    public abstract int time();

    public abstract void cleanup() throws IOException;

    public abstract void seek(int positionMs);

    protected final void checkPreload() {
        if (preloadEnabled && !calledPreload && !stopped && duration - time() <= TRACK_PRELOAD_THRESHOLD) {
            calledPreload = true;
            listener.preloadNextTrack();
        }
    }

    public static class CodecException extends Exception {

        CodecException() {
        }

        CodecException(@NotNull Throwable ex) {
            super(ex);
        }

        CodecException(String message) {
            super(message);
        }
    }
}
