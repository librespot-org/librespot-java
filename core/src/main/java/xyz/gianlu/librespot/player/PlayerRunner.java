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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.FloatControl;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class PlayerRunner implements Runnable, Closeable {
    public static final int VOLUME_STEPS = 64;
    public static final int VOLUME_MAX = 65536;
    private static final int VOLUME_STEP = 65536 / VOLUME_STEPS;
    private static final Logger LOGGER = Logger.getLogger(PlayerRunner.class);
    private static final AtomicInteger IDS = new AtomicInteger(0);
    private final Session session;
    private final LinesHolder lines;
    private final Player.Configuration conf;
    private final Listener listener;
    private final Map<Integer, TrackHandler> loadedTracks = new HashMap<>(3);
    private final BlockingQueue<CommandBundle> commands = new LinkedBlockingQueue<>();
    private LinesHolder.LineWrapper output;
    private Controller controller;

    PlayerRunner(@NotNull Session session, @NotNull LinesHolder lines, @NotNull Player.Configuration conf, @NotNull Listener listener) {
        this.session = session;
        this.lines = lines;
        this.conf = conf;
        this.listener = listener;

        Looper looper;
        new Thread(looper = new Looper(), "player-runner-" + looper.hashCode()).start();
    }

    private void sendCommand(@NotNull Command command, int id, Object... args) {
        commands.add(new CommandBundle(command, id, args));
    }

    @NotNull
    public TrackHandler load(@NotNull PlayableId playable, boolean play, int pos) {
        int id = IDS.getAndIncrement();

        TrackHandler handler = new TrackHandler(id, playable);
        sendCommand(Command.Load, id, handler, play, pos);
        return handler;
    }

    @Override
    public void run() {
        // TODO
    }

    @Nullable
    Controller controller() {
        if (controller == null) {
            if (output == null) return null;
            else controller = new Controller(output, conf.initialVolume());
        }

        return controller;
    }

    @Override
    public void close() throws IOException {
        commands.add(new CommandBundle(Command.Terminate, -1));
        // TODO: Close this
    }

    public void stopAll() {
        sendCommand(Command.StopAll, -1);
    }

    public enum Command {
        Load, Play, Pause, Stop, StopAll, Seek, Terminate
    }

    public interface Listener {
        void startedLoading(@NotNull TrackHandler handler);

        void finishedLoading(@NotNull TrackHandler handler, int pos, boolean play);

        void loadingError(@NotNull TrackHandler handler, @NotNull PlayableId track, @NotNull Exception ex);

        void endOfTrack(@NotNull TrackHandler handler);

        void preloadNextTrack(@NotNull TrackHandler handler);

        void playbackError(@NotNull TrackHandler handler, @NotNull Exception ex);

        void playbackHalted(@NotNull TrackHandler handler, int chunk);

        void playbackResumedFromHalt(@NotNull TrackHandler handler, int chunk, long diff);

        int getVolume();
    }

    public static class Controller {
        private final FloatControl masterGain;
        private int volume = 0;

        private Controller(@NotNull LinesHolder.LineWrapper line, int initialVolume) {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN))
                masterGain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            else
                masterGain = null;

            setVolume(initialVolume);
        }

        private double calcLogarithmic(int val) {
            return Math.log10((double) val / VOLUME_MAX) * 20f;
        }

        public void setVolume(int val) {
            this.volume = val;

            if (masterGain != null)
                masterGain.setValue((float) calcLogarithmic(val));
        }

        int volumeDown() {
            setVolume(volume - VOLUME_STEP);
            return volume;
        }

        int volumeUp() {
            setVolume(volume + VOLUME_STEP);
            return volume;
        }
    }

    public class TrackHandler implements AbsChunckedInputStream.HaltListener, Closeable {
        private final int id;
        private final PlayableId playable;
        public Metadata.Track track;
        public Metadata.Episode episode;
        private long playbackHaltedAt = 0;

        TrackHandler(int id, @NotNull PlayableId playable) {
            this.id = id;
            this.playable = playable;
        }

        private void load(boolean play, int pos) throws Codec.CodecException, IOException, LinesHolder.MixerException, MercuryClient.MercuryException, CdnManager.CdnException, ContentRestrictedException {
            listener.startedLoading(this);

            PlayableContentFeeder.LoadedStream stream = session.contentFeeder().load(playable, new VorbisOnlyAudioQuality(conf.preferredQuality()), this);
            track = stream.track;
            episode = stream.episode;

            int duration;
            if (playable instanceof EpisodeId) {
                duration = stream.episode.getDuration();
                LOGGER.info(String.format("Loaded episode, name: '%s', gid: %s", stream.episode.getName(), Utils.bytesToHex(playable.getGid())));
            } else if (playable instanceof TrackId) {
                duration = stream.track.getDuration();
                LOGGER.info(String.format("Loaded track, name: '%s', artists: '%s', gid: %s", stream.track.getName(), Utils.artistsToString(stream.track.getArtistList()), Utils.bytesToHex(playable.getGid())));
            } else {
                throw new IllegalArgumentException();
            }

            Codec codec;
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

            LOGGER.trace(String.format("Player ready for playback, codec: %s, fileId: %s", stream.in.codec(), stream.in.describe()));

            AudioFormat format = codec.getAudioFormat();
            if (output == null) {
                output = lines.getLineFor(conf, format);
            } else {
                if (!output.isCompatible(format))
                    throw new UnsupportedOperationException("Current line doesn't support this format!"); // FIXME
            }

            // TODO: Seek to position

            listener.finishedLoading(this, pos, play);

            // TODO: Play if needed
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
        public void close() {

        }

        boolean isTrack(@NotNull PlayableId id) {
            return playable.toSpotifyUri().equals(id.toSpotifyUri());
        }

        void seek(int pos) {
            sendCommand(Command.Seek, id, pos);
        }

        void pause() {
            sendCommand(Command.Pause, id);
        }

        void play() {
            sendCommand(Command.Play, id);
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
                            boolean play = (boolean) cmd.args[1];
                            int pos = (int) cmd.args[2];

                            loadedTracks.put(cmd.id, handler);

                            try {
                                handler.load(play, pos);
                            } catch (IOException | LinesHolder.MixerException | Codec.CodecException | ContentRestrictedException | MercuryClient.MercuryException | CdnManager.CdnException ex) {
                                listener.loadingError(handler, handler.playable, ex);
                            }
                        case Play: // TODO
                            break;
                        case Pause: // TODO
                            break;
                        case Stop: // TODO
                            break;
                        case Seek: // TODO
                            break;
                        case StopAll: // TODO
                            break;
                        case Terminate:
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
