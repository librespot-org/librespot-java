package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cdn.CdnManager;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.codecs.VorbisOnlyAudioQuality;
import xyz.gianlu.librespot.player.feeders.BaseFeeder;
import xyz.gianlu.librespot.player.feeders.CdnFeeder;
import xyz.gianlu.librespot.player.feeders.StorageFeeder;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Gianlu
 */
public class TrackHandler implements PlayerRunner.Listener, Closeable, AbsChunckedInputStream.HaltListener {
    private static final Logger LOGGER = Logger.getLogger(TrackHandler.class);
    private final BlockingQueue<CommandBundle> commands = new LinkedBlockingQueue<>();
    private final Session session;
    private final LinesHolder lines;
    private final Player.Configuration conf;
    private final Listener listener;
    private BaseFeeder feeder;
    private Metadata.Track track;
    private Metadata.Episode episode;
    private PlayerRunner playerRunner;
    private volatile boolean stopped = false;
    private long haltedAt = -1;

    TrackHandler(@NotNull Session session, @NotNull LinesHolder lines, @NotNull Player.Configuration conf, @NotNull Listener listener) {
        this.session = session;
        this.lines = lines;
        this.conf = conf;
        this.listener = listener;

        Looper looper;
        new Thread(looper = new Looper(), "track-handler-" + looper.hashCode()).start();
    }

    @NotNull
    private PlayerRunner createRunner(@NotNull BaseFeeder.LoadedStream stream) throws Codec.CodecException, IOException, LinesHolder.MixerException {
        return new PlayerRunner(stream.in, stream.normalizationData, lines, conf, this, track == null ? episode.getDuration() : track.getDuration());
    }

    private void load(@NotNull PlayableId id, boolean play, int pos) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException, ContentRestrictedException {
        if (feeder == null) feeder = BaseFeeder.feederFor(session, id, conf);

        listener.startedLoading(this);

        BaseFeeder.LoadedStream stream;
        try {
            stream = feeder.load(id, new VorbisOnlyAudioQuality(conf.preferredQuality()), this);
        } catch (CdnFeeder.CanNotAvailable ex) {
            LOGGER.warn(String.format("Cdn not available for %s, using storage", Utils.bytesToHex(id.getGid())));
            feeder = new StorageFeeder(session, id);
            stream = feeder.load(id, new VorbisOnlyAudioQuality(conf.preferredQuality()), this);
        }

        track = stream.track;
        episode = stream.episode;

        if (stopped) return;

        if (id instanceof EpisodeId)
            LOGGER.info(String.format("Loaded episode, name: '%s', gid: %s", episode.getName(), Utils.bytesToHex(id.getGid())));
        else if (id instanceof TrackId)
            LOGGER.info(String.format("Loaded track, name: '%s', artists: '%s', gid: %s", track.getName(), Utils.artistsToString(track.getArtistList()), Utils.bytesToHex(id.getGid())));

        loadRunner(id, stream, play, pos);
    }

    private void loadRunner(@NotNull PlayableId id, @NotNull BaseFeeder.LoadedStream stream, boolean play, int pos) throws IOException {
        try {
            if (playerRunner != null) playerRunner.stop();
            playerRunner = createRunner(stream);
            new Thread(playerRunner, "player-runner-" + playerRunner.hashCode()).start();

            playerRunner.seek(pos);

            listener.finishedLoading(this, pos, play);

            if (play) playerRunner.play();
        } catch (LinesHolder.MixerException | Codec.CodecException ex) {
            listener.loadingError(this, id, ex);
        }

        if (stopped && playerRunner != null) playerRunner.stop();
    }

    private void sendCommand(@NotNull Command command, Object... args) {
        if (stopped) throw new IllegalStateException("Looper is stopped!");
        commands.add(new CommandBundle(command, args));
    }

    void sendPlay() {
        sendCommand(Command.Play);
    }

    void sendSeek(int pos) {
        sendCommand(Command.Seek, pos);
    }

    void sendPause() {
        sendCommand(Command.Pause);
    }

    void sendStop() {
        sendCommand(Command.Stop);
    }

    boolean isStopped() {
        return stopped;
    }

    void sendLoad(@NotNull PlayableId track, boolean play, int pos) {
        sendCommand(Command.Load, track, play, pos);
    }

    @Override
    public void endOfTrack() {
        listener.endOfTrack(this);
    }

    @Override
    public void playbackError(@NotNull Exception ex) {
        listener.playbackError(this, ex);
    }

    @Override
    public void preloadNextTrack() {
        listener.preloadNextTrack(this);
    }

    @Override
    public int getVolume() {
        return 400; // TODO
    }

    @Nullable
    PlayerRunner.Controller controller() {
        return playerRunner == null ? null : playerRunner.controller();
    }

    @Override
    public void close() {
        stopped = true;
        if (playerRunner != null) playerRunner.stop();
        commands.add(new CommandBundle(Command.Terminate));
    }

    boolean isTrack(@NotNull PlayableId id) {
        return (track != null && track.hasGid() && Arrays.equals(id.getGid(), track.getGid().toByteArray()))
                || (episode != null && episode.hasGid() && Arrays.equals(id.getGid(), episode.getGid().toByteArray()));
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
    public void streamReadHalted(int chunk, long time) {
        haltedAt = time;
        listener.playbackHalted(this, chunk);
    }

    @Override
    public void streamReadResumed(int chunk, long time) {
        if (haltedAt != -1) {
            long diff = time - haltedAt;
            haltedAt = -1;

            listener.playbackResumedFromHalt(this, chunk, diff);
        }
    }

    public enum Command {
        Load, Play, Pause,
        Stop, Seek, Terminate
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
    }

    private class Looper implements Runnable {

        @Override
        public void run() {
            try {
                while (!stopped) {
                    CommandBundle cmd = commands.take();
                    switch (cmd.cmd) {
                        case Load:
                            PlayableId id = (PlayableId) cmd.args[0];

                            try {
                                load(id, (Boolean) cmd.args[1], (Integer) cmd.args[2]);
                            } catch (IOException | MercuryClient.MercuryException | CdnManager.CdnException | ContentRestrictedException ex) {
                                listener.loadingError(TrackHandler.this, id, ex);
                            }
                            break;
                        case Play:
                            if (playerRunner != null) playerRunner.play();
                            break;
                        case Pause:
                            if (playerRunner != null) playerRunner.pause();
                            break;
                        case Stop:
                            if (playerRunner != null) playerRunner.stop();
                            close();
                            break;
                        case Seek:
                            if (playerRunner != null) playerRunner.seek((Integer) cmd.args[0]);
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
        private final Object[] args;

        private CommandBundle(@NotNull Command cmd, Object... args) {
            this.cmd = cmd;
            this.args = args;
        }
    }
}
