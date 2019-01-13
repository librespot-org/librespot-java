package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.TrackId;

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
    private final StreamFeeder feeder;
    private PlayerRunner playerRunner;
    private Metadata.Track track;
    private volatile boolean stopped = false;

    TrackHandler(@NotNull Session session, @NotNull LinesHolder lines, @NotNull CacheManager cacheManager, @NotNull Player.Configuration conf, @NotNull Listener listener) {
        this.session = session;
        this.lines = lines;
        this.conf = conf;
        this.listener = listener;
        this.feeder = new StreamFeeder(session, cacheManager);

        new Thread(new Looper()).start();
    }

    private void load(@NotNull TrackId id, boolean play, int pos) throws IOException, MercuryClient.MercuryException {
        StreamFeeder.LoadedStream stream = feeder.load(id, new StreamFeeder.VorbisOnlyAudioQuality(conf.preferredQuality()));
        track = stream.track;

        if (stopped) return;

        LOGGER.info(String.format("Loaded track, name: '%s', artists: '%s', gid: %s", track.getName(), Utils.artistsToString(track.getArtistList()), Utils.bytesToHex(id.getGid())));

        try {
            if (playerRunner != null) playerRunner.stop();
            playerRunner = new PlayerRunner(stream.in, stream.normalizationData, lines, conf, this, track.getDuration());
            new Thread(playerRunner).start();

            playerRunner.seek(pos);

            listener.finishedLoading(this, play);

            if (play) playerRunner.play();
        } catch (PlayerRunner.PlayerException ex) {
            listener.loadingError(this, id, ex);
        }

        if (stopped && playerRunner != null) playerRunner.stop();
    }

    private void sendCommand(@NotNull Command command, Object... args) {
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

    void sendLoad(@NotNull TrackId track, boolean play, int pos) {
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
    }

    @Nullable
    public Metadata.Track track() {
        return track;
    }

    boolean isTrack(@NotNull TrackId id) {
        return track != null && track.hasGid() && Arrays.equals(id.getGid(), track.getGid().toByteArray());
    }

    public enum Command {
        Load, Play, Pause,
        Stop, Seek
    }

    public interface Listener {
        void finishedLoading(@NotNull TrackHandler handler, boolean play);

        void loadingError(@NotNull TrackHandler handler, @NotNull TrackId track, @NotNull Exception ex);

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
                            TrackId id = (TrackId) cmd.args[0];

                            try {
                                load(id, (Boolean) cmd.args[1], (Integer) cmd.args[2]);
                            } catch (IOException | MercuryClient.MercuryException ex) {
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
