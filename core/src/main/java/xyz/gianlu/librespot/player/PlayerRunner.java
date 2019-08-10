package xyz.gianlu.librespot.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
    private final MixingLine mixing = new MixingLine();
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
                    SourceDataLine line = LineHelper.getLineFor(conf, Output.OUTPUT_FORMAT);
                    line.open(Output.OUTPUT_FORMAT);

                    output = new Output(line, null, null, conf);
                } catch (LineUnavailableException ex) {
                    throw new RuntimeException("Failed opening line!", ex);
                }
                break;
            case PIPE:
                File pipe = conf.outputPipe();
                if (pipe == null || !pipe.exists() || !pipe.canWrite())
                    throw new IllegalArgumentException("Invalid pipe file: " + pipe);

                output = new Output(null, pipe, null, conf);
                break;
            case STDOUT:
                output = new Output(null, null, System.out, conf);
                break;
            default:
                throw new IllegalArgumentException("Unknown output: " + conf.output());
        }

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

    @Nullable
    Controller controller() {
        return output.controller();
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

    public enum Command {
        PlayMixer, PauseMixer, StopMixer, TerminateMixer,
        Load, PushToMixer, Stop, Seek
    }

    public interface Listener {
        void startedLoading(@NotNull TrackHandler handler);

        void finishedLoading(@NotNull TrackHandler handler, int pos);

        void mixerError(@NotNull Exception ex);

        void loadingError(@NotNull TrackHandler handler, @NotNull PlayableId track, @NotNull Exception ex);

        void endOfTrack(@NotNull TrackHandler handler);

        void preloadNextTrack(@NotNull TrackHandler handler);

        void playbackError(@NotNull TrackHandler handler, @NotNull Exception ex);

        void playbackHalted(@NotNull TrackHandler handler, int chunk);

        void playbackResumedFromHalt(@NotNull TrackHandler handler, int chunk, long diff);

        void crossfadeNextTrack(@NotNull TrackHandler handler);
    }

    public interface GainInterpolator {
        float interpolate(float x);
    }

    /**
     * Lookup interpolator based on <a href="https://www.embeddedrelated.com/showcode/345.php>this snippet</a>.
     */
    private static class LookupInterpolator implements GainInterpolator {
        private final float[] tx;
        private final float[] ty;

        public LookupInterpolator(float[] x, float[] y) {
            this.tx = x;
            this.ty = y;
        }

        /**
         * Used to parse the 'fade_curve' JSON array
         */
        @NotNull
        public static LookupInterpolator fromJson(@NotNull JsonArray curve) {
            float[] x = new float[curve.size()];
            float[] y = new float[curve.size()];
            for (int i = 0; i < curve.size(); i++) {
                JsonObject obj = curve.get(i).getAsJsonObject();
                x[i] = obj.get("x").getAsFloat();
                y[i] = obj.get("y").getAsFloat();
            }

            return new LookupInterpolator(x, y);
        }

        @Override
        public float interpolate(float ix) {
            if (ix > tx[tx.length - 1]) return tx[tx.length - 1];
            else if (ix < tx[0]) return tx[0];

            for (int i = 0; i < tx.length - 1; i++) {
                if (ix >= tx[i] && ix <= tx[i + 1]) {
                    float o_low = ty[i]; // Output (table) low value
                    float i_low = tx[i]; // Input (X-axis) low value
                    float i_delta = tx[i + 1] - tx[i]; // Spread between the two adjacent input values
                    float o_delta = ty[i + 1] - ty[i]; // Spread between the two adjacent table output values

                    if (o_delta == 0) return o_low;
                    else return o_low + ((ix - i_low) * (long) o_delta) / i_delta;
                }
            }

            throw new IllegalArgumentException();
        }
    }

    private static class LinearIncreasingInterpolator implements GainInterpolator {
        @Override
        public float interpolate(float x) {
            return x;
        }
    }

    private static class LinearDecreasingInterpolator implements GainInterpolator {
        @Override
        public float interpolate(float x) {
            return 1 - x;
        }
    }

    private static class Output implements Closeable {
        private static final AudioFormat OUTPUT_FORMAT = new AudioFormat(44100, 16, 2, true, false);
        private final SourceDataLine line;
        private final File pipe;
        private final Controller controller;
        private OutputStream out;

        @Contract("null, null, null, _ -> fail")
        Output(@Nullable SourceDataLine line, @Nullable File pipe, @Nullable OutputStream out, @NotNull Player.Configuration conf) {
            if (line == null && pipe == null && out == null) throw new IllegalArgumentException();

            this.line = line;
            this.pipe = pipe;
            this.out = out;

            if (line != null) controller = new Controller(line, conf.initialVolume());
            else controller = null;
        }

        @Nullable
        private Controller controller() {
            return controller;
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
    }

    static class Controller {
        private final FloatControl masterGain;

        private Controller(@NotNull SourceDataLine line, int initialVolume) {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN))
                masterGain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            else
                masterGain = null;

            setVolume(initialVolume);
        }

        private double calcLogarithmic(int val) {
            return Math.log10((double) val / VOLUME_MAX) * 20f;
        }

        void setVolume(int val) {
            if (masterGain != null) masterGain.setValue((float) calcLogarithmic(val));
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
        private final int crossfadeDuration; // Represent both start and end crossfade duration
        private final Object readyLock = new Object();
        public Metadata.Track track;
        public Metadata.Episode episode;
        private long playbackHaltedAt = 0;
        private volatile boolean calledPreload = false;
        private Codec codec;
        private volatile boolean closed = false;
        private MixingLine.MixingOutput out;
        private volatile boolean calledCrossfade = false;
        private GainInterpolator startInterpolator = null;
        private GainInterpolator endInterpolator = null;

        TrackHandler(int id, @NotNull PlayableId playable) {
            this.id = id;
            this.playable = playable;
            this.crossfadeDuration = conf.crossfadeDuration();

            setupInterpolators();
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

            switch (stream.in.codec()) {
                case VORBIS:
                    codec = new VorbisCodec(stream.in, stream.normalizationData, conf, duration);
                    break;
                case MP3:
                    try {
                        codec = new Mp3Codec(stream.in, stream.normalizationData, conf, duration);
                    } catch (BitstreamException ex) {
                        throw new IOException(ex);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown codec: " + stream.in.codec());
            }

            if (!output.getFormat().matches(codec.getAudioFormat()))
                throw new UnsupportedOperationException(codec.getAudioFormat().toString()); // FIXME: Support light conversion

            LOGGER.trace(String.format("Loaded codec (%s), fileId: %s, format: %s", stream.in.codec(), stream.in.describe(), codec.getAudioFormat()));

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
                codec.cleanup();
            } catch (IOException ignored) {
            }
        }

        boolean isTrack(@NotNull PlayableId id) {
            return playable.toSpotifyUri().equals(id.toSpotifyUri());
        }

        void seek(int pos) {
            sendCommand(Command.Seek, id, pos);
        }

        void pushToMixer() {
            sendCommand(Command.PushToMixer, id);
        }

        int time() throws Codec.CannotGetTimeException {
            return codec.time();
        }

        private void shouldPreload() {
            if (calledPreload || codec == null) return;

            if (!conf.preloadEnabled() && crossfadeDuration == 0) { // Force preload if crossfade is enabled
                calledPreload = true;
                return;
            }

            try {
                if (codec.remaining() <= TimeUnit.SECONDS.toMillis(15) + crossfadeDuration) {
                    listener.preloadNextTrack(this);
                    calledPreload = true;
                }
            } catch (Codec.CannotGetTimeException ex) {
                calledPreload = true;
            }
        }

        private void shouldCrossfade() {
            if (calledCrossfade || crossfadeDuration == 0) return;

            try {
                if (codec.remaining() <= crossfadeDuration) {
                    listener.crossfadeNextTrack(this);
                    calledCrossfade = true;
                }
            } catch (Codec.CannotGetTimeException ex) {
                calledCrossfade = true;
            }
        }

        private void setupInterpolators() {
            startInterpolator = new LinearIncreasingInterpolator();
            endInterpolator = new LinearDecreasingInterpolator();
        }

        private void updateGain() {
            int pos;
            try {
                pos = codec.time();
            } catch (Codec.CannotGetTimeException ex) {
                return;
            }

            if (pos <= crossfadeDuration) {
                setGain(startInterpolator.interpolate(((float) pos) / crossfadeDuration));
            } else if (codec.duration() - pos <= crossfadeDuration) {
                int crossfadePos = crossfadeDuration - (codec.duration() - pos);
                setGain(endInterpolator.interpolate(((float) crossfadePos) / crossfadeDuration));
            }
        }

        @Override
        public void run() {
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
                shouldCrossfade();
                updateGain();

                try {
                    if (codec.readSome(out.stream()) == -1) {
                        listener.endOfTrack(this);
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
    }
}
