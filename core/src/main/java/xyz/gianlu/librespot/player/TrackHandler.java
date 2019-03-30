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
import xyz.gianlu.librespot.player.feeders.EpisodeStreamFeeder;
import xyz.gianlu.librespot.player.feeders.TrackStreamFeeder;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Gianlu
 */
public class TrackHandler implements PlayerRunner.Listener, Closeable {
    private static final Logger LOGGER = Logger.getLogger(TrackHandler.class);
    private final BlockingQueue<CommandBundle> commands = new LinkedBlockingQueue<>();
    private final Session session;
    private final LinesHolder lines;
    private final Player.Configuration conf;
    private final Listener listener;
    private TrackStreamFeeder trackFeeder;
    private EpisodeStreamFeeder episodeFeeder;
    private Metadata.Track track;
    private Metadata.Episode episode;
    private PlayerRunner playerRunner;
    private volatile boolean stopped = false;

    TrackHandler(@NotNull Session session, @NotNull LinesHolder lines, @NotNull Player.Configuration conf, @NotNull Listener listener) {
        this.session = session;
        this.lines = lines;
        this.conf = conf;
        this.listener = listener;

        Looper looper;
        new Thread(looper = new Looper(), "track-handler-" + looper.hashCode()).start();
    }

    private void load(@NotNull TrackId id, boolean play, int pos) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException {
        if (trackFeeder == null)
            this.trackFeeder = new TrackStreamFeeder(session);

        listener.startedLoading(this);

        TrackStreamFeeder.LoadedStream stream = trackFeeder.load(id, new TrackStreamFeeder.VorbisOnlyAudioQuality(conf.preferredQuality()), conf.useCdn());
        track = stream.track;

        if (stopped) return;

        LOGGER.info(String.format("Loaded track, name: '%s', artists: '%s', gid: %s", track.getName(), Utils.artistsToString(track.getArtistList()), Utils.bytesToHex(id.getGid())));

        try {
            if (playerRunner != null) playerRunner.stop();
            playerRunner = new PlayerRunner(stream.in, stream.normalizationData, lines, conf, this, track.getDuration());
            new Thread(playerRunner, "player-runner-" + playerRunner.hashCode()).start();

            playerRunner.seek(pos);

            listener.finishedLoading(this, pos, play);

            if (play) playerRunner.play();
        } catch (PlayerRunner.PlayerException ex) {
            listener.loadingError(this, id, ex);
        }

        if (stopped && playerRunner != null) playerRunner.stop();
    }

    private void load(@NotNull EpisodeId id, boolean play, int pos) throws IOException, MercuryClient.MercuryException, EpisodeStreamFeeder.LoaderException {
        if (episodeFeeder == null)
            this.episodeFeeder = new EpisodeStreamFeeder(session);

        listener.startedLoading(this);

        EpisodeStreamFeeder.LoadedStream stream = episodeFeeder.load(id, conf.useCdn());
        episode = stream.episode;

        if (stopped) return;

        LOGGER.info(String.format("Loaded episode, name: '%s', gid: %s", episode.getName(), Utils.bytesToHex(id.getGid())));

        try {
            if (playerRunner != null) playerRunner.stop();
            playerRunner = new PlayerRunner(stream.in, null, lines, conf, this, track.getDuration());
            new Thread(playerRunner, "player-runner-" + playerRunner.hashCode()).start();

            playerRunner.seek(pos);

            listener.finishedLoading(this, pos, play);

            if (play) playerRunner.play();
        } catch (PlayerRunner.PlayerException ex) {
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
        LOGGER.fatal("Playback failed!", ex);
    }

    @Override
    public void preloadNextTrack() {
        listener.preloadNextTrack(this);
    }

    @Override
    public int getVolume() {
        return session.spirc().deviceState().getVolume();
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
        return track != null && track.hasGid() && Arrays.equals(id.getGid(), track.getGid().toByteArray());
    }

    public int getPosition() {
        return playerRunner == null ? 0 : playerRunner.time();
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
                                if (id instanceof TrackId)
                                    load((TrackId) id, (Boolean) cmd.args[1], (Integer) cmd.args[2]);
                                else if (id instanceof EpisodeId)
                                    load((EpisodeId) id, (Boolean) cmd.args[1], (Integer) cmd.args[2]);
                                else
                                    throw new IllegalArgumentException("Unknown PlayableId: " + id);
                            } catch (IOException | MercuryClient.MercuryException | CdnManager.CdnException | EpisodeStreamFeeder.LoaderException ex) {
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
