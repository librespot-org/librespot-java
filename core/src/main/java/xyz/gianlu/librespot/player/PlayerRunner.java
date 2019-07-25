package xyz.gianlu.librespot.player;

import com.spotify.metadata.proto.Metadata;
import javazoom.jl.decoder.BitstreamException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
    private final Listener listener;
    private final Map<Integer, TrackHandler> loadedTracks = new HashMap<>(3);
    private final BlockingQueue<CommandBundle> commands = new LinkedBlockingQueue<>();
    private final Object pauseLock = new Object();
    private final SourceDataLine output;
    private final Controller controller;
    private volatile boolean closed = false;
    private volatile boolean paused = true;
    private TrackHandler playingHandler = null;

    PlayerRunner(@NotNull Session session, @NotNull Player.Configuration conf, @NotNull Listener listener) {
        this.session = session;
        this.conf = conf;
        this.listener = listener;

        try {
            AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
            this.output = LineHelper.getLineFor(conf, format);
            this.output.open(format);
            this.controller = new Controller(output, conf.initialVolume());
        } catch (LineUnavailableException ex) {
            throw new RuntimeException(ex);
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

                if (playingHandler == null) {
                    paused = true;
                    continue;
                }

                playingHandler.shouldPreload();

                TrackHandler handler = playingHandler;
                try {
                    if (playingHandler.codec.readSome(output) == -1) {
                        playingHandler = null;
                        paused = true;

                        handler.stop();
                        listener.endOfTrack(handler);
                    }
                } catch (IOException | Codec.CodecException | NullPointerException ex) {
                    if (closed) break;
                    if (handler != null && handler.closed) continue;

                    listener.playbackError(handler, ex);
                    paused = true;
                }
            }
        }

        output.drain();
        output.close();
    }

    @NotNull
    Controller controller() {
        return controller;
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
        Load, SetPlaying, Stop, Seek
    }

    public interface Listener {
        void startedLoading(@NotNull TrackHandler handler);

        void finishedLoading(@NotNull TrackHandler handler, int pos);

        void loadingError(@NotNull TrackHandler handler, @NotNull PlayableId track, @NotNull Exception ex);

        void endOfTrack(@NotNull TrackHandler handler);

        void preloadNextTrack(@NotNull TrackHandler handler);

        void playbackError(@NotNull TrackHandler handler, @NotNull Exception ex);

        void playbackHalted(@NotNull TrackHandler handler, int chunk);

        void playbackResumedFromHalt(@NotNull TrackHandler handler, int chunk, long diff);
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

    public class TrackHandler implements AbsChunckedInputStream.HaltListener, Closeable {
        private final int id;
        private final PlayableId playable;
        public Metadata.Track track;
        public Metadata.Episode episode;
        private long playbackHaltedAt = 0;
        private volatile boolean calledPreload = false;
        private Codec codec;
        private volatile boolean closed = false;

        TrackHandler(int id, @NotNull PlayableId playable) {
            this.id = id;
            this.playable = playable;
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
                throw new UnsupportedOperationException(codec.getAudioFormat().toString()); // FIXME

            LOGGER.trace(String.format("Loaded codec (%s), fileId: %s, format: %s", stream.in.codec(), stream.in.describe(), codec.getAudioFormat()));

            codec.seek(pos);

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

        @Override
        public void close() throws IOException {
            closed = true;
            codec.cleanup();
        }

        private void silentClose() {
            try {
                close();
            } catch (IOException ex) {
                LOGGER.debug("An exception occurred while closing the handler!", ex);
            }
        }

        boolean isTrack(@NotNull PlayableId id) {
            return playable.toSpotifyUri().equals(id.toSpotifyUri());
        }

        void seek(int pos) {
            sendCommand(Command.Seek, id, pos);
        }

        void setPlaying() {
            sendCommand(Command.SetPlaying, id);
        }

        private void shouldPreload() {
            if (calledPreload) return;

            if (!conf.preloadEnabled()) {
                calledPreload = true;
                return;
            }

            try {
                if (codec.remaining() < TimeUnit.SECONDS.toMillis(15)) {
                    listener.preloadNextTrack(this);
                    calledPreload = true;
                }
            } catch (Codec.CannotGetTimeException ex) {
                calledPreload = true;
            }
        }

        void stop() {
            sendCommand(Command.Stop, id);
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
                        case SetPlaying:
                            playingHandler = loadedTracks.get(cmd.id);
                            if (playingHandler == null) throw new IllegalArgumentException();
                            break;
                        case Stop:
                            TrackHandler hh = loadedTracks.remove(cmd.id);
                            if (hh != null) {
                                hh.silentClose();
                                if (playingHandler == hh) playingHandler = null;
                            }
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
                                h.silentClose();

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

    private class CommandBundle {
        private final Command cmd;
        private final int id;
        private final Object[] args;

        private CommandBundle(@NotNull Command cmd, int id, Object... args) {
            this.cmd = cmd;
            this.id = id;
            this.args = args;
        }
    }
}
