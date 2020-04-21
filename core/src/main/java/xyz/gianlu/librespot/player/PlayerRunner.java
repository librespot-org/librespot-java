package xyz.gianlu.librespot.player;

import com.spotify.metadata.Metadata;
import javazoom.jl.decoder.BitstreamException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.AsyncWorker;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.codecs.Mp3Codec;
import xyz.gianlu.librespot.player.codecs.VorbisCodec;
import xyz.gianlu.librespot.player.codecs.VorbisOnlyAudioQuality;
import xyz.gianlu.librespot.player.crossfade.CrossfadeController;
import xyz.gianlu.librespot.player.feeders.PlayableContentFeeder;
import xyz.gianlu.librespot.player.feeders.cdn.CdnManager;
import xyz.gianlu.librespot.player.mixing.LineHelper;
import xyz.gianlu.librespot.player.mixing.MixingLine;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class PlayerRunner implements Runnable, Closeable {
    public static final int VOLUME_MAX = 65536;
    public static final AudioFormat OUTPUT_FORMAT = new AudioFormat(44100, 16, 2, true, false);
    private static final Logger LOGGER = Logger.getLogger(PlayerRunner.class);
    private static final AtomicInteger IDS = new AtomicInteger(0);
    private final Session session;
    private final Player.Configuration conf;
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory((r) -> "player-runner-writer-" + r.hashCode()));
    private final Listener listener;
    private final Map<Integer, TrackHandler> loadedTracks = new HashMap<>(3);
    private final AsyncWorker<CommandBundle> asyncWorker;
    private final Object pauseLock = new Object();
    private final Output output;
    private final MixingLine mixing = new MixingLine(OUTPUT_FORMAT);
    private volatile boolean closed = false;
    private volatile boolean paused = true;
    private TrackHandler firstHandler = null;
    private TrackHandler secondHandler = null;

    PlayerRunner(@NotNull Session session, @NotNull Player.Configuration conf, @NotNull Listener listener) {
        this.session = session;
        this.conf = conf;
        this.listener = listener;

        switch (conf.output()) {
            case MIXER:
                try {
                    output = new Output(Output.Type.MIXER, mixing, conf, null, null);
                } catch (LineUnavailableException ex) {
                    throw new IllegalStateException("Failed opening line!", ex);
                }
                break;
            case PIPE:
                File pipe = conf.outputPipe();
                if (pipe == null || !pipe.exists() || !pipe.canWrite())
                    throw new IllegalArgumentException("Invalid pipe file: " + pipe);

                try {
                    output = new Output(Output.Type.PIPE, mixing, conf, pipe, null);
                } catch (LineUnavailableException ignored) {
                    throw new IllegalStateException(); // Cannot be thrown
                }
                break;
            case STDOUT:
                try {
                    output = new Output(Output.Type.STREAM, mixing, conf, null, System.out);
                } catch (LineUnavailableException ignored) {
                    throw new IllegalStateException(); // Cannot be thrown
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown output: " + conf.output());
        }

        output.setVolume(conf.initialVolume());

        asyncWorker = new AsyncWorker<>("player-runner-looper", this::handleCommand);
    }

    /**
     * Pauses the mixer and then releases the {@link javax.sound.sampled.Line} if acquired.
     *
     * @return Whether the line was released.
     */
    public boolean pauseAndRelease() {
        pauseMixer();
        while (!paused) {
            synchronized (pauseLock) {
                try {
                    pauseLock.wait(100);
                } catch (InterruptedException ignored) {
                }
            }
        }

        return output.releaseLine();
    }

    /**
     * Stops the mixer and then releases the {@link javax.sound.sampled.Line} if acquired.
     */
    public boolean stopAndRelease() {
        stopMixer();
        while (!paused) {
            synchronized (pauseLock) {
                try {
                    pauseLock.wait(100);
                } catch (InterruptedException ignored) {
                }
            }
        }

        return output.releaseLine();
    }

    private void sendCommand(@NotNull Command command, int id, Object... args) {
        asyncWorker.submit(new CommandBundle(command, id, args));
    }

    @NotNull
    TrackHandler load(@NotNull PlayableId playable, int pos) {
        int id = IDS.getAndIncrement();
        TrackHandler handler = new TrackHandler(id, playable);
        sendCommand(Command.Load, id, handler, pos);
        return handler;
    }

    @Override
    public void run() {
        LOGGER.trace("PlayerRunner is starting");

        byte[] buffer = new byte[Codec.BUFFER_SIZE * 2];

        boolean started = false;
        while (!closed) {
            if (paused) {
                output.stop();
                started = false;

                synchronized (pauseLock) {
                    try {
                        pauseLock.wait();
                    } catch (InterruptedException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            } else {
                if (!started) started = output.start();

                try {
                    int count = mixing.read(buffer);
                    output.write(buffer, count);
                } catch (IOException | LineUnavailableException ex) {
                    if (closed) break;

                    paused = true;
                    listener.mixerError(ex);
                }
            }
        }

        try {
            output.drain();
            output.close();
        } catch (IOException ignored) {
        }

        LOGGER.trace("PlayerRunner is shutting down");
    }

    @Override
    public void close() throws IOException {
        asyncWorker.submit(new CommandBundle(Command.TerminateMixer, -1));

        closed = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        for (TrackHandler handler : loadedTracks.values())
            handler.close();

        loadedTracks.clear();

        firstHandler = null;
        secondHandler = null;

        output.close();
        asyncWorker.close();
        executorService.shutdown();
    }

    void pauseMixer() {
        sendCommand(Command.PauseMixer, -1);
    }

    void playMixer() {
        sendCommand(Command.PlayMixer, -1);
    }

    void stopMixer() {
        sendCommand(Command.StopMixer, -1);
    }

    void setVolume(int volume) {
        output.setVolume(volume);
    }

    /**
     * Handles a command from {@link PlayerRunner#asyncWorker}, MUST not be called manually.
     *
     * @param cmd The command
     */
    private void handleCommand(@NotNull CommandBundle cmd) {
        TrackHandler handler;
        switch (cmd.cmd) {
            case Load:
                handler = (TrackHandler) cmd.args[0];
                loadedTracks.put(cmd.id, handler);

                try {
                    handler.load((int) cmd.args[1]);
                } catch (IOException | LineHelper.MixerException | Codec.CodecException | ContentRestrictedException | MercuryClient.MercuryException | CdnManager.CdnException ex) {
                    listener.loadingError(handler, handler.playable, ex);
                    handler.close();
                }
                break;
            case PushToMixer:
                handler = loadedTracks.get(cmd.id);
                if (handler == null) break;

                if (firstHandler == null) {
                    firstHandler = handler;
                    firstHandler.setOut(mixing.firstOut());
                } else if (secondHandler == null) {
                    secondHandler = handler;
                    secondHandler.setOut(mixing.secondOut());
                } else {
                    throw new IllegalStateException();
                }

                executorService.execute(handler);
                break;
            case RemoveFromMixer:
                handler = loadedTracks.get(cmd.id);
                if (handler == null) break;
                handler.clearOut();
                break;
            case Stop:
                handler = loadedTracks.get(cmd.id);
                if (handler != null) handler.close();
                break;
            case Seek:
                handler = loadedTracks.get(cmd.id);
                if (handler == null) break;

                if (!handler.isReady())
                    handler.waitReady();

                boolean shouldAbortCrossfade = false;
                if (handler == firstHandler && secondHandler != null) {
                    secondHandler.close();
                    secondHandler = null;
                    shouldAbortCrossfade = true;
                } else if (handler == secondHandler && firstHandler != null) {
                    firstHandler.close();
                    firstHandler = null;
                    shouldAbortCrossfade = true;
                }

                if (handler.codec != null) {
                    if (shouldAbortCrossfade) handler.abortCrossfade();

                    output.flush();
                    if (handler.out != null) handler.out.stream().emptyBuffer();
                    handler.codec.seek((Integer) cmd.args[0]);
                }

                listener.finishedSeek(handler);
                break;
            case PlayMixer:
                paused = false;
                synchronized (pauseLock) {
                    pauseLock.notifyAll();
                }
                break;
            case PauseMixer:
                paused = true;
                break;
            case StopMixer:
                paused = true;
                for (TrackHandler h : new ArrayList<>(loadedTracks.values()))
                    h.close();

                firstHandler = null;
                secondHandler = null;
                loadedTracks.clear();

                synchronized (pauseLock) {
                    pauseLock.notifyAll();
                }
                break;
            case TerminateMixer:
                return;
            default:
                throw new IllegalArgumentException("Unknown command: " + cmd.cmd);
        }
    }

    public enum Command {
        PlayMixer, PauseMixer, StopMixer, TerminateMixer,
        Load, PushToMixer, Stop, Seek, RemoveFromMixer
    }

    public enum PushToMixerReason {
        None, Next,
        Prev, Fade
    }

    public interface Listener {
        void startedLoading(@NotNull TrackHandler handler);

        void finishedLoading(@NotNull TrackHandler handler, int pos);

        void mixerError(@NotNull Exception ex);

        void loadingError(@NotNull TrackHandler handler, @NotNull PlayableId track, @NotNull Exception ex);

        void endOfTrack(@NotNull TrackHandler handler, @Nullable String uri, boolean fadeOut);

        void preloadNextTrack(@NotNull TrackHandler handler);

        void playbackError(@NotNull TrackHandler handler, @NotNull Exception ex);

        void playbackHalted(@NotNull TrackHandler handler, int chunk);

        void playbackResumedFromHalt(@NotNull TrackHandler handler, int chunk, long diff);

        void crossfadeNextTrack(@NotNull TrackHandler handler, @Nullable String uri);

        @NotNull
        Map<String, String> metadataFor(@NotNull PlayableId id);

        void finishedSeek(@NotNull TrackHandler handler);

        void abortedCrossfade(@NotNull TrackHandler handler);
    }

    private static class Output implements Closeable {
        private final File pipe;
        private final MixingLine mixing;
        private final Player.Configuration conf;
        private final Type type;
        private SourceDataLine line;
        private OutputStream out;
        private int lastVolume = -1;

        Output(@NotNull Type type, @NotNull MixingLine mixing, @NotNull Player.Configuration conf, @Nullable File pipe, @Nullable OutputStream out) throws LineUnavailableException {
            this.conf = conf;
            this.mixing = mixing;
            this.type = type;
            this.pipe = pipe;
            this.out = out;

            switch (type) {
                case MIXER:
                    acquireLine();
                    break;
                case PIPE:
                    if (pipe == null) throw new IllegalArgumentException();
                    break;
                case STREAM:
                    if (out == null) throw new IllegalArgumentException();
                    break;
                default:
                    throw new IllegalArgumentException(String.valueOf(type));
            }
        }

        private static float calcLogarithmic(int val) {
            return (float) (Math.log10((double) val / VOLUME_MAX) * 20f);
        }

        private void acquireLine() throws LineUnavailableException {
            if (line != null) return;

            line = LineHelper.getLineFor(conf, OUTPUT_FORMAT);
            line.open(OUTPUT_FORMAT);

            if (lastVolume != -1) setVolume(lastVolume);
        }

        void flush() {
            if (line != null) line.flush();
        }

        void stop() {
            if (line != null) line.stop();
        }

        boolean start() {
            if (line != null) {
                line.start();
                return true;
            }

            return false;
        }

        void write(byte[] buffer, int len) throws IOException, LineUnavailableException {
            if (type == Type.MIXER) {
                acquireLine();
                line.write(buffer, 0, len);
            } else if (type == Type.PIPE) {
                if (out == null) {
                    if (!pipe.exists()) {
                        try {
                            Process p = new ProcessBuilder().command("mkfifo " + pipe.getAbsolutePath())
                                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                            p.waitFor();
                            if (p.exitValue() != 0)
                                LOGGER.warn(String.format("Failed creating pipe! {exit: %d}", p.exitValue()));
                            else
                                LOGGER.info("Created pipe: " + pipe);
                        } catch (InterruptedException ex) {
                            throw new IllegalStateException(ex);
                        }
                    }

                    out = new FileOutputStream(pipe, true);
                }

                out.write(buffer, 0, len);
            } else if (type == Type.STREAM) {
                out.write(buffer, 0, len);
            } else {
                throw new IllegalStateException();
            }
        }

        void drain() {
            if (line != null) line.drain();
        }

        @Override
        public void close() throws IOException {
            if (line != null) line.close();
            if (out != null) out.close();
        }

        @NotNull
        public AudioFormat getFormat() {
            if (line != null) return line.getFormat();
            else return OUTPUT_FORMAT;
        }

        void setVolume(int volume) {
            lastVolume = volume;

            if (line != null) {
                FloatControl ctrl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                if (ctrl != null) {
                    mixing.setGlobalGain(1);
                    ctrl.setValue(calcLogarithmic(volume));
                    return;
                }
            }

            // Cannot set volume through line
            mixing.setGlobalGain(((float) volume) / VOLUME_MAX);
        }

        boolean releaseLine() {
            if (line == null) return false;

            line.close();
            line = null;
            return true;
        }

        enum Type {
            MIXER, PIPE, STREAM
        }
    }

    private static class CommandBundle {
        private final Command cmd;
        private final int id;
        private final Object[] args;

        private CommandBundle(@NotNull Command cmd, int id, Object... args) {
            this.cmd = cmd;
            this.id = id;
            this.args = args;
        }
    }

    public class TrackHandler implements HaltListener, Closeable, Runnable {
        private final int id;
        private final PlayableId playable;
        private final Object writeLock = new Object();
        private final Object readyLock = new Object();
        private Metadata.Track track;
        private Metadata.Episode episode;
        private CrossfadeController crossfade;
        private long playbackHaltedAt = 0;
        private volatile boolean calledPreload = false;
        private Codec codec;
        private volatile boolean closed = false;
        private MixingLine.MixingOutput out;
        private PushToMixerReason pushReason = PushToMixerReason.None;
        private volatile boolean calledCrossfade = false;
        private boolean abortCrossfade = false;

        TrackHandler(int id, @NotNull PlayableId playable) {
            this.id = id;
            this.playable = playable;
        }

        private void setOut(@NotNull MixingLine.MixingOutput out) {
            this.out = out;
            out.toggle(true);

            synchronized (writeLock) {
                writeLock.notifyAll();
            }
        }

        private void setGain(float gain) {
            if (out == null) return;
            out.gain(gain);
        }

        private void clearOut() {
            if (out == null) return;
            out.toggle(false);
            out.clear();
            out = null;
        }

        boolean isReady() {
            if (closed) throw new IllegalStateException("The handler is closed!");
            return codec != null;
        }

        void waitReady() {
            synchronized (readyLock) {
                if (codec == null) {
                    try {
                        readyLock.wait();
                    } catch (InterruptedException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
        }

        private void load(int pos) throws Codec.CodecException, IOException, LineHelper.MixerException, MercuryClient.MercuryException, CdnManager.CdnException, ContentRestrictedException {
            listener.startedLoading(this);

            PlayableContentFeeder.LoadedStream stream = session.contentFeeder().load(playable, new VorbisOnlyAudioQuality(conf.preferredQuality()), this);
            track = stream.track;
            episode = stream.episode;

            int duration;
            if (playable instanceof EpisodeId && stream.episode != null) {
                duration = stream.episode.getDuration();
                LOGGER.info(String.format("Loaded episode, name: '%s', gid: %s", stream.episode.getName(), Utils.bytesToHex(playable.getGid())));
            } else if (playable instanceof TrackId && stream.track != null) {
                duration = stream.track.getDuration();
                LOGGER.info(String.format("Loaded track, name: '%s', artists: '%s', gid: %s", stream.track.getName(), Utils.artistsToString(stream.track.getArtistList()), Utils.bytesToHex(playable.getGid())));
            } else {
                throw new IllegalArgumentException();
            }

            try {
                Map<String, String> metadata = listener.metadataFor(playable);
                crossfade = new CrossfadeController(duration, metadata, conf);
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("Failed retrieving metadata for " + playable);
                crossfade = new CrossfadeController(duration, conf);
            }

            switch (stream.in.codec()) {
                case VORBIS:
                    codec = new VorbisCodec(output.getFormat(), stream.in, stream.normalizationData, conf, duration);
                    break;
                case MP3:
                    try {
                        codec = new Mp3Codec(output.getFormat(), stream.in, stream.normalizationData, conf, duration);
                    } catch (BitstreamException ex) {
                        throw new IOException(ex);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown codec: " + stream.in.codec());
            }

            LOGGER.trace(String.format("Loaded codec (%s), fileId: %s, format: %s", stream.in.codec(), stream.in.describe(), codec.getAudioFormat()));

            if (pos == 0 && crossfade.fadeInEnabled()) pos = crossfade.fadeInStartTime();
            codec.seek(pos);

            synchronized (readyLock) {
                readyLock.notifyAll();
            }

            listener.finishedLoading(this, pos);
        }

        @Override
        public void streamReadHalted(int chunk, long time) {
            playbackHaltedAt = time;
            listener.playbackHalted(this, chunk);
        }

        @Override
        public void streamReadResumed(int chunk, long time) {
            listener.playbackResumedFromHalt(this, chunk, time - playbackHaltedAt);
        }

        @Nullable
        public Metadata.Track track() {
            return track;
        }

        @Nullable
        public Metadata.Episode episode() {
            return episode;
        }

        void stop() {
            sendCommand(Command.Stop, id);
        }

        @Override
        public void close() {
            if (closed) return;

            loadedTracks.remove(id);
            if (firstHandler == this) firstHandler = null;
            else if (secondHandler == this) secondHandler = null;

            closed = true;

            synchronized (writeLock) {
                writeLock.notifyAll();
            }

            try {
                clearOut();
                if (codec != null) codec.close();
                codec = null;
            } catch (IOException ignored) {
            }
        }

        boolean isPlayable(@NotNull PlayableId id) {
            return !closed && playable.toSpotifyUri().equals(id.toSpotifyUri());
        }

        void seek(int pos) {
            sendCommand(Command.Seek, id, pos);
        }

        void pushToMixer(@NotNull PushToMixerReason reason) {
            pushReason = reason;
            sendCommand(Command.PushToMixer, id);
        }

        void removeFromMixer() {
            sendCommand(Command.RemoveFromMixer, id);
        }

        int time() throws Codec.CannotGetTimeException {
            return codec == null ? 0 : Math.max(0, codec.time());
        }

        private void shouldPreload() {
            if (calledPreload || codec == null) return;

            if (!conf.preloadEnabled() && !crossfade.fadeOutEnabled()) { // Force preload if crossfade is enabled
                calledPreload = true;
                return;
            }

            try {
                if (codec.time() + TimeUnit.SECONDS.toMillis(15) >= crossfade.fadeOutStartTime()) {
                    listener.preloadNextTrack(this);
                    calledPreload = true;
                }
            } catch (Codec.CannotGetTimeException ex) {
                calledPreload = true;
            }
        }

        private boolean updateCrossfade() {
            int pos;
            try {
                pos = codec.time();
            } catch (Codec.CannotGetTimeException ex) {
                calledCrossfade = true;
                return false;
            }

            if (abortCrossfade && calledCrossfade) {
                abortCrossfade = false;
                listener.abortedCrossfade(this);
                return false;
            }

            if (!calledCrossfade && crossfade.shouldStartNextTrack(pos)) {
                listener.crossfadeNextTrack(this, crossfade.fadeOutUri());
                calledCrossfade = true;
            } else if (crossfade.shouldStop(pos)) {
                return true;
            } else {
                setGain(crossfade.getGain(pos));
            }

            return false;
        }

        void abortCrossfade() {
            abortCrossfade = true;
        }

        @Override
        public void run() {
            LOGGER.trace("PlayerRunner.TrackHandler is starting");

            waitReady();

            int seekTo = -1;
            if (pushReason == PushToMixerReason.Fade) {
                seekTo = crossfade.fadeInStartTime();
            }

            if (seekTo != -1) codec.seek(seekTo);

            while (!closed) {
                if (out == null) {
                    synchronized (writeLock) {
                        try {
                            writeLock.wait();
                        } catch (InterruptedException ex) {
                            throw new IllegalStateException(ex);
                        }
                    }
                }

                if (closed) return;
                if (out == null) break;

                shouldPreload();
                if (updateCrossfade()) {
                    listener.endOfTrack(this, crossfade.fadeOutUri(), true);
                    break;
                }

                try {
                    if (codec.readSome(out.stream()) == -1) {
                        listener.endOfTrack(this, crossfade.fadeOutUri(), false);
                        break;
                    }
                } catch (IOException | Codec.CodecException ex) {
                    if (closed) return;

                    listener.playbackError(this, ex);
                    break;
                }
            }

            close();

            LOGGER.trace("PlayerRunner.TrackHandler is shutting down");
        }

        boolean isInMixer() {
            return firstHandler == this || secondHandler == this;
        }
    }
}
