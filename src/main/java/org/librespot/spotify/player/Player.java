package org.librespot.spotify.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.mercury.MercuryClient;
import org.librespot.spotify.mercury.MercuryRequests;
import org.librespot.spotify.mercury.model.TrackId;
import org.librespot.spotify.proto.Metadata;
import org.librespot.spotify.proto.Spirc;
import org.librespot.spotify.spirc.FrameListener;
import org.librespot.spotify.spirc.SpotifyIrc;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public class Player implements FrameListener {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final Session session;
    private final SpotifyIrc spirc;
    private final Spirc.State.Builder state;
    private final Configuration conf = new Configuration();
    private PlayerRunner playerRunner;

    public Player(@NotNull Session session) {
        this.session = session;
        this.spirc = session.spirc();
        this.state = initState();

        spirc.addListener(this);
    }

    @NotNull
    private Spirc.State.Builder initState() {
        return Spirc.State.newBuilder()
                .setPositionMeasuredAt(0)
                .setPositionMs(0)
                .setShuffle(false)
                .setRepeat(false)
                .setStatus(Spirc.PlayStatus.kPlayStatusStop);
    }

    @Override
    public void frame(@NotNull Spirc.Frame frame) {
        switch (frame.getTyp()) {
            case kMessageTypeLoad:
                handleLoad(frame);
                break;
            case kMessageTypePlay:
                handlePlay();
                break;
            case kMessageTypePause:
                handlePause();
                break;
            case kMessageTypePlayPause:
                if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPlay) handlePause();
                else if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPause) handlePlay();
                break;
            case kMessageTypeSeek:
                // TODO
                break;
        }
    }

    private void loadTrack(boolean play) throws IOException, MercuryClient.MercuryException {
        Spirc.TrackRef ref = state.getTrack(state.getPlayingTrackIndex());
        Metadata.Track track = session.mercury().requestSync(MercuryRequests.getTrack(new TrackId(ref)));
        LOGGER.info(String.format("Loading track, name: %s, artists: %s", track.getName(), track.getArtistList()));

        Metadata.AudioFile file = conf.preferredQuality.getFile(track);
        if (file == null) {
            LOGGER.fatal(String.format("Couldn't find file for %s!", conf.preferredQuality));
            return;
        }

        byte[] key = session.audioKey().getAudioKey(track, file);
        AudioFileStreaming audioStreaming = new AudioFileStreaming(session, file, key);
        audioStreaming.open();

        InputStream in = audioStreaming.stream();

        NormalizationData normalizationData = NormalizationData.read(in);
        LOGGER.trace(String.format("Loaded normalization data, track_gain: %.2f, track_peak: %.2f, album_gain: %.2f, album_peak: %.2f",
                normalizationData.track_gain_db, normalizationData.track_peak, normalizationData.album_gain_db, normalizationData.album_peak));

        if (in.skip(0xa7) != 0xa7)
            throw new IOException("Couldn't skip 0xa7 bytes!");

        try {
            if (playerRunner != null) playerRunner.stop();
            playerRunner = new PlayerRunner(audioStreaming, normalizationData, conf);
            new Thread(playerRunner).start();

            if (play) {
                state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
                playerRunner.seek(state.getPositionMs());
                playerRunner.play();
            } else {
                state.setStatus(Spirc.PlayStatus.kPlayStatusPause);
            }
        } catch (PlayerRunner.PlayerException ex) {
            LOGGER.fatal("Failed starting playback!", ex);
        }
    }

    private void handleLoad(@NotNull Spirc.Frame frame) {
        if (!spirc.deviceState().getIsActive()) {
            spirc.deviceState()
                    .setIsActive(true)
                    .setBecameActiveAt(System.currentTimeMillis());
        }

        state.setPlayingTrackIndex(frame.getState().getPlayingTrackIndex());
        state.clearTrack();
        state.addAllTrack(frame.getState().getTrackList());
        state.setContextUri(frame.getState().getContextUri());
        state.setRepeat(frame.getState().getRepeat());
        state.setShuffle(frame.getState().getShuffle());

        if (state.getTrackCount() > 0) {
            state.setPositionMs(frame.getState().getPositionMs());
            state.setPositionMeasuredAt(System.currentTimeMillis());

            try {
                loadTrack(frame.getState().getStatus() == Spirc.PlayStatus.kPlayStatusPlay);
            } catch (IOException | MercuryClient.MercuryException ex) {
                state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
                LOGGER.fatal("Failed loading track!", ex);
            }
        } else {
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
        }

        spirc.deviceStateUpdated(state);
    }

    private void handlePlay() {
        if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPause) {
            if (playerRunner != null) playerRunner.play();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            state.setPositionMeasuredAt(System.currentTimeMillis());
            spirc.deviceStateUpdated(state);
        }
    }

    private void handlePause() {
        if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPlay) {
            if (playerRunner != null) playerRunner.pause();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPause);

            long now = System.currentTimeMillis();
            int pos = state.getPositionMs();
            int diff = (int) (now - state.getPositionMeasuredAt());
            state.setPositionMs(pos + diff);
            state.setPositionMeasuredAt(now);
            spirc.deviceStateUpdated(state);
        }
    }

    private enum AudioQuality {
        VORBIS_96(Metadata.AudioFile.Format.OGG_VORBIS_96),
        VORBIS_160(Metadata.AudioFile.Format.OGG_VORBIS_160),
        VORBIS_320(Metadata.AudioFile.Format.OGG_VORBIS_320);

        private final Metadata.AudioFile.Format format;

        AudioQuality(@NotNull Metadata.AudioFile.Format format) {
            this.format = format;
        }

        @Nullable
        private Metadata.AudioFile getFile(@NotNull Metadata.Track track) {
            for (Metadata.AudioFile file : track.getFileList()) {
                if (file.getFormat() == this.format)
                    return file;
            }

            return null;
        }
    }

    public static class Configuration {
        public final AudioQuality preferredQuality;
        public final float normalisationPregain;

        public Configuration() {
            this.preferredQuality = AudioQuality.VORBIS_160;
            this.normalisationPregain = 0;
        }
    }
}
