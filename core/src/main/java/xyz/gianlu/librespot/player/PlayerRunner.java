package xyz.gianlu.librespot.player;

import com.spotify.metadata.proto.Metadata;
import javazoom.jl.decoder.BitstreamException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class PlayerRunner implements Runnable, Closeable {
    public static final int VOLUME_STEPS = 64;
    public static final int VOLUME_MAX = 65536;
    private static final Logger LOGGER = Logger.getLogger(PlayerRunner.class);
    private static final AtomicInteger IDS = new AtomicInteger(0);
    private final Session session;
    private final Player.Configuration conf;
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory((r) -> "player-runner-writer-" + r.hashCode()));
    private final Listener listener;
    private final Map<Integer, TrackHandler> loadedTracks = new HashMap<>(3);
    private final BlockingQueue<CommandBundle> commands = new LinkedBlockingQueue<>();
    private final Object pauseLock = new Object();
    private final Output output;
    private final MixingLine mixing = new MixingLine(Output.OUTPUT_FORMAT);
    private volatile boolean closed = false;
    private volatile boolean paused = true;
    private TrackHandler firstHandler = null;
    private TrackHandler secondHandler = null;
    private volatile boolean calledCrossfade = false;

    PlayerRunner(@NotNull Session session, @NotNull Player.Configuration conf, @NotNull Listener listener) {
        this.session = session;
        this.conf = conf;
        this.listener = listener;

        switch (conf.output()) {
            case MIXER:
                try {
                    SourceDataLine line = LineHelper.getLineFor(conf, Output.OUTPUT_FORMAT);
                    line.open(Output.OUTPUT_FORMAT);

                    output = new Output(mixing, line, null, null);
                } catch (LineUnavailableException ex) {
                    throw new RuntimeException("Failed opening line!", ex);
                }
                break;
            case PIPE:
                File pipe = conf.outputPipe();
                if (pipe == null || !pipe.exists() || !pipe.canWrite())
                    throw new IllegalArgumentException("Invalid pipe file: " + pipe);

                output = new Output(mixing, null, pipe, null);
                break;
            case STDOUT:
                output = new Output(mixing, null, null, System.out);
                break;
            default:
                throw new IllegalArgumentException("Unknown output: " + conf.output());
        }

        output.setVolume(conf.initialVolume());

        Looper looper;
        new Thread(looper = new Looper(), "player-runner-looper-" + looper.hashCode()).start();
    }

    private void sendCommand(@NotNull Command command, int id, Object... args) {
        commands.add(new CommandBundle(command, id, args));
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
        byte[] buffer = new byte[Codec.BUFFER_SIZE * 2];

        while (!closed) {
            if (paused) {
                output.stop();

                synchronized (pauseLock) {
                    try {
                        pauseLock.wait();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            } else {
                output.start();

                try {
                    int count = mixing.read(buffer);
                    int r = count % mixing.getFrameSize();
                    if (r != 0) count += mixing.read(buffer, count, mixing.getFrameSize() - r);
                    output.write(buffer, 0, count);
                } catch (IOException ex) {
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
    }

    @Override
    public void close() throws IOException {
        commands.add(new CommandBundle(Command.TerminateMixer, -1));

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

    public enum Command {
        PlayMixer, PauseMixer, StopMixer, TerminateMixer,
        Load, PushToMixer, Stop, Seek
    }

    public enum PushToMixerReason {
        None, Next, Prev, Fade
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
    }

    private static class Output implements Closeable {
        private static final AudioFormat OUTPUT_FORMAT = new AudioFormat(44100, 16, 2, true, false);
        private final SourceDataLine line;
        private final File pipe;
        private final MixingLine mixing;
        private OutputStream out;

        @Contract("_, null, null, null -> fail")
        Output(@NotNull MixingLine mixing, @Nullable SourceDataLine line, @Nullable File pipe, @Nullable OutputStream out) {
            if (line == null && pipe == null && out == null) throw new IllegalArgumentException();

            this.mixing = mixing;
            this.line = line;
            this.pipe = pipe;
            this.out = out;
        }

        private static float calcLogarithmic(int val) {
            return (float) (Math.log10((double) val / VOLUME_MAX) * 20f);
        }

        void stop() {
            if (line != null) line.stop();
        }

        void start() {
            if (line != null) line.start();
        }

        void write(byte[] buffer, int off, int len) throws IOException {
            if (line != null) {
                line.write(buffer, off, len);
            } else {
                if (out == null) {
                    if (pipe == null) throw new IllegalStateException();
                    out = new FileOutputStream(pipe, true);
                }

                out.write(buffer, off, len);
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

    private class Looper implements Runnable {

        @Override
        public void run() {
            try {
                while (true) {
                    CommandBundle cmd = commands.take();
                    switch (cmd.cmd) {
                        case Load:
                            TrackHandler handler = (TrackHandler) cmd.args[0];
                            loadedTracks.put(cmd.id, handler);

                            try {
                                handler.load((int) cmd.args[1]);
                            } catch (IOException | LineHelper.MixerException | Codec.CodecException | ContentRestrictedException | MercuryClient.MercuryException | CdnManager.CdnException ex) {
                                listener.loadingError(handler, handler.playable, ex);
                            }
                            break;
                        case PushToMixer:
                            TrackHandler hhh = loadedTracks.get(cmd.id);
                            if (hhh == null) throw new IllegalArgumentException();

                            if (firstHandler == null) {
                                firstHandler = hhh;
                                firstHandler.setOut(mixing.firstOut());
                            } else if (secondHandler == null) {
                                secondHandler = hhh;
                                secondHandler.setOut(mixing.secondOut());
                            } else {
                                throw new IllegalStateException();
                            }

                            executorService.execute(hhh);
                            break;
                        case Stop:
                            TrackHandler hh = loadedTracks.remove(cmd.id);
                            if (hh != null) hh.close();
                            break;
                        case Seek:
                            loadedTracks.get(cmd.id).codec.seek((Integer) cmd.args[0]);
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
                            for (TrackHandler h : loadedTracks.values())
                                h.close();

                            firstHandler = null;
                            secondHandler = null;
                            loadedTracks.clear();
                            break;
                        case TerminateMixer:
                            return;
                    }
                }
            } catch (InterruptedException ex) {
                LOGGER.fatal("Failed handling command!", ex);
            }
        }
    }

    public class TrackHandler implements AbsChunckedInputStream.HaltListener, Closeable, Runnable {
        private final int id;
        private final PlayableId playable;
        private final Object writeLock = new Object();
        private final Object readyLock = new Object();
        public Metadata.Track track;
        public Metadata.Episode episode;
        private CrossfadeController crossfade;
        private long playbackHaltedAt = 0;
        private volatile boolean calledPreload = false;
        private Codec codec;
        private volatile boolean closed = false;
        private MixingLine.MixingOutput out;
        private PushToMixerReason pushReason = PushToMixerReason.None;

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

        private void clearOut() throws IOException {
            if (out == null) return;
            out.toggle(false);
            out.clear();
            out = null;
        }

        boolean isReady() {
            return codec != null;
        }

        void waitReady() {
            synchronized (readyLock) {
                if (codec == null) {
                    try {
                        readyLock.wait();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
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

            crossfade = new CrossfadeController(duration, listener.metadataFor(playable), conf);

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
            } catch (IOException ignored) {
            }
        }

        boolean isTrack(@NotNull PlayableId id) {
            return playable.toSpotifyUri().equals(id.toSpotifyUri());
        }

        void seek(int pos) {
            sendCommand(Command.Seek, id, pos);
        }

        void pushToMixer(@NotNull PushToMixerReason reason) {
            pushReason = reason;
            sendCommand(Command.PushToMixer, id);
        }

        int time() throws Codec.CannotGetTimeException {
            return codec.time();
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

        @Override
        public void run() {
            waitReady();

            int seekTo = -1;
            if (pushReason == PushToMixerReason.Fade) {
                seekTo = crossfade.fadeInStartTime();
            } else if (pushReason == PushToMixerReason.Next) {
                // TODO
            } else if (pushReason == PushToMixerReason.Prev) {
                // TODO
            }

            if (seekTo != -1) codec.seek(seekTo);

            while (!closed) {
                if (out == null) {
                    synchronized (writeLock) {
                        try {
                            writeLock.wait();
                        } catch (InterruptedException ex) {
                            throw new RuntimeException(ex);
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
        }

        boolean isInMixer() {
            return firstHandler == this || secondHandler == this;
        }
    }
}
