package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.common.proto.Spirc;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Gianlu
 */
public class TrackHandler implements PlayerRunner.Listener, Closeable {
    private static final Logger LOGGER = Logger.getLogger(TrackHandler.class);
    private final BlockingQueue<CommandBundle> commands = new LinkedBlockingQueue<>();
    private final Session session;
    private final CacheManager cacheManager;
    private final Player.PlayerConfiguration conf;
    private final Listener listener;
    private final Looper looper;
    private PlayerRunner playerRunner;
    private Metadata.Track track;

    TrackHandler(@NotNull Session session, @NotNull CacheManager cacheManager, @NotNull Player.PlayerConfiguration conf, @NotNull Listener listener) {
        this.session = session;
        this.cacheManager = cacheManager;
        this.conf = conf;
        this.listener = listener;

        new Thread(looper = new Looper()).start();
    }

    @Nullable
    private static Metadata.Track pickAlternativeIfNecessary(@NotNull Metadata.Track track) {
        if (track.getFileCount() > 0) return track;

        for (Metadata.Track alt : track.getAlternativeList()) {
            if (alt.getFileCount() > 0) {
                Metadata.Track.Builder builder = track.toBuilder();
                builder.clearFile();
                builder.addAllFile(alt.getFileList());
                return builder.build();
            }
        }

        return null;
    }

    private void load(@NotNull Spirc.TrackRef ref, boolean play, int pos) throws IOException, MercuryClient.MercuryException {
        track = session.mercury().sendSync(MercuryRequests.getTrack(TrackId.fromTrackRef(ref)));
        track = pickAlternativeIfNecessary(track);
        if (track == null) {
            LOGGER.fatal("Couldn't find playable track: " + Utils.bytesToHex(ref.getGid()));
            return;
        }

        LOGGER.info(String.format("Loading track, name: '%s', artists: '%s', play: %b, pos: %d", track.getName(), Utils.toString(track.getArtistList()), play, pos));

        Metadata.AudioFile file = conf.preferredQuality().getFile(track);
        if (file == null) {
            file = AudioQuality.getAnyVorbisFile(track);
            if (file == null) {
                LOGGER.fatal(String.format("Couldn't find any Vorbis file, available: %s", AudioQuality.listFormats(track)));
                return;
            } else {
                LOGGER.warn(String.format("Using %s because preferred %s couldn't be found.", file, conf.preferredQuality()));
            }
        }

        byte[] key = session.audioKey().getAudioKey(track, file);
        AudioFileStreaming audioStreaming = new AudioFileStreaming(session, cacheManager, file, key);
        audioStreaming.open();

        InputStream in = audioStreaming.stream();
        NormalizationData normalizationData = NormalizationData.read(in);
        LOGGER.trace(String.format("Loaded normalization data, track_gain: %.2f, track_peak: %.2f, album_gain: %.2f, album_peak: %.2f",
                normalizationData.track_gain_db, normalizationData.track_peak, normalizationData.album_gain_db, normalizationData.album_peak));

        if (in.skip(0xa7) != 0xa7)
            throw new IOException("Couldn't skip 0xa7 bytes!");

        try {
            if (playerRunner != null) playerRunner.stop();
            playerRunner = new PlayerRunner(audioStreaming, normalizationData, conf, this, track.getDuration());
            playerRunner.initController(session.spirc().deviceState());
            new Thread(playerRunner).start();

            playerRunner.seek(pos);

            listener.finishedLoading(this, play);

            if (play) playerRunner.play();
        } catch (PlayerRunner.PlayerException ex) {
            LOGGER.fatal("Failed starting playback!", ex);
            listener.loadingError(this, ex);
        }
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

    void sendLoad(@NotNull Spirc.TrackRef ref, boolean play, int pos) {
        sendCommand(Command.Load, ref, play, pos);
    }

    @Override
    public void endOfTrack() {
        listener.endOfTrack(this);
    }

    @Override
    public void playbackError(@NotNull Exception ex) {
        LOGGER.fatal("Playback failed!", ex);
    }

    @Nullable
    PlayerRunner.Controller controller() {
        return playerRunner == null ? null : playerRunner.controller();
    }

    @Override
    public void close() {
        if (playerRunner != null) playerRunner.stop();
        looper.stop();
    }

    @Nullable
    public Metadata.Track track() {
        return track;
    }

    boolean isTrack(Spirc.TrackRef ref) {
        return track != null && ref.getGid().equals(track.getGid());
    }

    public enum AudioQuality {
        VORBIS_96(Metadata.AudioFile.Format.OGG_VORBIS_96),
        VORBIS_160(Metadata.AudioFile.Format.OGG_VORBIS_160),
        VORBIS_320(Metadata.AudioFile.Format.OGG_VORBIS_320);

        private final Metadata.AudioFile.Format format;

        AudioQuality(@NotNull Metadata.AudioFile.Format format) {
            this.format = format;
        }

        @Nullable
        public static Metadata.AudioFile getAnyVorbisFile(@NotNull Metadata.Track track) {
            for (Metadata.AudioFile file : track.getFileList()) {
                Metadata.AudioFile.Format fmt = file.getFormat();
                if (fmt == Metadata.AudioFile.Format.OGG_VORBIS_96
                        || fmt == Metadata.AudioFile.Format.OGG_VORBIS_160
                        || fmt == Metadata.AudioFile.Format.OGG_VORBIS_320) {
                    return file;
                }
            }

            return null;
        }

        @NotNull
        public static List<Metadata.AudioFile.Format> listFormats(Metadata.Track track) {
            List<Metadata.AudioFile.Format> list = new ArrayList<>(track.getFileCount());
            for (Metadata.AudioFile file : track.getFileList()) list.add(file.getFormat());
            return list;
        }

        @Nullable Metadata.AudioFile getFile(@NotNull Metadata.Track track) {
            for (Metadata.AudioFile file : track.getFileList()) {
                if (file.getFormat() == this.format)
                    return file;
            }

            return null;
        }
    }

    public enum Command {
        Load, Play, Pause,
        Stop, Seek
    }

    public interface Listener {
        void finishedLoading(@NotNull TrackHandler handler, boolean play);

        void loadingError(@NotNull TrackHandler handler, @NotNull Exception ex);

        void endOfTrack(@NotNull TrackHandler handler);
    }

    private class Looper implements Runnable {
        private volatile boolean stopped = false;

        @Override
        public void run() {
            try {
                while (!stopped) {
                    CommandBundle cmd = commands.take();
                    switch (cmd.cmd) {
                        case Load:
                            try {
                                load((Spirc.TrackRef) cmd.args[0], (Boolean) cmd.args[1], (Integer) cmd.args[2]);
                            } catch (IOException | MercuryClient.MercuryException ex) {
                                listener.loadingError(TrackHandler.this, ex);
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
                            stop();
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

        private void stop() {
            stopped = true;
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
