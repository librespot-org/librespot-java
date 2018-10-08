package org.librespot.spotify.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.librespot.spotify.Utils;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * @author Gianlu
 */
public class Player implements FrameListener, PlayerRunner.Listener {
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
            case kMessageTypeNotify:
                if (spirc.deviceState().getIsActive() && frame.getDeviceState().getIsActive()) {
                    spirc.deviceState().setIsActive(false);
                    state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
                    if (playerRunner != null) playerRunner.stop();
                    stateUpdated();
                }
                break;
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
            case kMessageTypeNext:
                handleNext();
                break;
            case kMessageTypePrev:
                handlePrev();
                break;
            case kMessageTypeSeek:
                handleSeek(frame.getPosition());
                break;
            case kMessageTypeReplace:
                updatedTracks(frame);
                stateUpdated();
                break;
            case kMessageTypeRepeat:
                state.setRepeat(frame.getState().getRepeat());
                stateUpdated();
                break;
            case kMessageTypeShuffle:
                state.setShuffle(frame.getState().getShuffle());
                handleShuffle();
                break;
            case kMessageTypeVolume:
                spirc.deviceState().setVolume(frame.getVolume());
                if (playerRunner != null) playerRunner.controller().setVolume(frame.getVolume());
                stateUpdated();
                break;
            case kMessageTypeVolumeDown:
                if (playerRunner != null) {
                    spirc.deviceState().setVolume(playerRunner.controller().volumeDown());
                    stateUpdated();
                }
                break;
            case kMessageTypeVolumeUp:
                if (playerRunner != null) {
                    spirc.deviceState().setVolume(playerRunner.controller().volumeUp());
                    stateUpdated();
                }
                break;
        }
    }

    private void stateUpdated() {
        spirc.deviceStateUpdated(state);
    }

    private void handlePrev() {
        if (getPosition() < 3000) {
            if (conf.pauseWhenLoading) {
                handlePause();
                stateUpdated();
            }

            List<Spirc.TrackRef> queueTracks = new ArrayList<>();
            Iterator<Spirc.TrackRef> iter = state.getTrackList().iterator();
            while (iter.hasNext()) {
                Spirc.TrackRef track = iter.next();
                if (track.getQueued()) {
                    queueTracks.add(track);
                    iter.remove();
                }
            }

            int current = state.getPlayingTrackIndex();
            int newIndex;
            if (current > 0) newIndex = current - 1;
            else if (state.getRepeat()) newIndex = state.getTrackCount() - 1;
            else newIndex = 0;

            for (int i = 0; i < queueTracks.size(); i++)
                state.getTrackList().add(newIndex + 1 + i, queueTracks.get(i));

            state.setPlayingTrackIndex(newIndex);
            state.setPositionMs(0);
            state.setPositionMeasuredAt(System.currentTimeMillis());

            safeLoadTrack(true, 0);
        } else {
            state.setPositionMs(0);
            state.setPositionMeasuredAt(System.currentTimeMillis());
            if (playerRunner != null) playerRunner.seek(0);
        }

        stateUpdated();
    }

    private int getPosition() {
        int diff = (int) (System.currentTimeMillis() - state.getPositionMeasuredAt());
        return state.getPositionMs() + diff;
    }

    private void shuffleTracks() {
        List<Spirc.TrackRef> tracks = new ArrayList<>(state.getTrackList());
        if (state.getPlayingTrackIndex() != 0) {
            Collections.swap(tracks, 0, state.getPlayingTrackIndex());
            state.setPlayingTrackIndex(0);
        }

        for (int i = tracks.size(); i > 1; i--)
            Collections.swap(tracks, i - 1, session.random().nextInt(i - 1) + 1);

        state.clearTrack();
        state.addAllTrack(tracks);
    }

    private void handleShuffle() {
        if (state.getShuffle()) {
            shuffleTracks();
            stateUpdated();
        }
    }

    private void handleSeek(int pos) {
        state.setPositionMs(pos);
        state.setPositionMeasuredAt(System.currentTimeMillis());
        if (playerRunner != null) playerRunner.seek(pos);
        stateUpdated();
    }

    private void loadTrack(boolean play, int pos) throws IOException, MercuryClient.MercuryException {
        state.setStatus(Spirc.PlayStatus.kPlayStatusLoading);
        stateUpdated();

        Spirc.TrackRef ref = state.getTrack(state.getPlayingTrackIndex());
        Metadata.Track track = session.mercury().requestSync(MercuryRequests.getTrack(new TrackId(ref)));
        LOGGER.info(String.format("Loading track, name: '%s', artists: '%s', play: %b, pos: %d", track.getName(), Utils.toString(track.getArtistList()), play, pos));

        Metadata.AudioFile file = conf.preferredQuality.getFile(track);
        if (file == null) {
            file = AudioQuality.getAnyVorbisFile(track);
            if (file == null) {
                LOGGER.fatal(String.format("Couldn't find any Vorbis file, available: %s", AudioQuality.listFormats(track)));
                state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
                return;
            } else {
                LOGGER.warn(String.format("Using %s because preferred %s couldn't be found.", file, conf.preferredQuality));
            }
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
            playerRunner = new PlayerRunner(audioStreaming, normalizationData, spirc.deviceState(), conf, this);
            new Thread(playerRunner).start();

            if (play) {
                state.setStatus(Spirc.PlayStatus.kPlayStatusLoading);
                playerRunner.seek(pos);
                playerRunner.play();
            } else {
                state.setStatus(Spirc.PlayStatus.kPlayStatusPause);
            }
        } catch (PlayerRunner.PlayerException ex) {
            LOGGER.fatal("Failed starting playback!", ex);
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
        }
    }

    private void updatedTracks(@NotNull Spirc.Frame frame) {
        state.setPlayingTrackIndex(frame.getState().getPlayingTrackIndex());
        state.clearTrack();
        state.addAllTrack(frame.getState().getTrackList());
        state.setContextUri(frame.getState().getContextUri());
        state.setRepeat(frame.getState().getRepeat());
        state.setShuffle(frame.getState().getShuffle());

        if (state.getShuffle())
            shuffleTracks();
    }

    private void handleLoad(@NotNull Spirc.Frame frame) {
        if (!spirc.deviceState().getIsActive()) {
            spirc.deviceState()
                    .setIsActive(true)
                    .setBecameActiveAt(System.currentTimeMillis());
        }

        updatedTracks(frame);

        if (state.getTrackCount() > 0) {
            int pos = frame.getState().getPositionMs();
            state.setPositionMs(pos);
            state.setPositionMeasuredAt(System.currentTimeMillis());

            safeLoadTrack(frame.getState().getStatus() == Spirc.PlayStatus.kPlayStatusPlay, pos);
            stateUpdated();
        } else {
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
            stateUpdated();
        }
    }

    private void safeLoadTrack(boolean play, int pos) {
        try {
            loadTrack(play, pos);
        } catch (IOException | MercuryClient.MercuryException ex) {
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
            LOGGER.fatal("Failed loading track!", ex);
        }
    }

    private void handlePlay() {
        if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPause) {
            if (playerRunner != null) playerRunner.play();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            state.setPositionMeasuredAt(System.currentTimeMillis());
            stateUpdated();
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
            stateUpdated();
        }
    }

    private void handleNext() {
        if (conf.pauseWhenLoading) {
            handlePause();
            stateUpdated();
        }

        int newTrack = consumeQueuedTrack();
        boolean play = true;
        if (newTrack >= state.getTrackCount()) {
            newTrack = 0;
            play = state.getRepeat();
        }

        state.setPlayingTrackIndex(newTrack);
        state.setPositionMs(0);
        state.setPositionMeasuredAt(System.currentTimeMillis());

        safeLoadTrack(play, 0);
        stateUpdated();
    }

    private int consumeQueuedTrack() {
        int current = state.getPlayingTrackIndex();
        if (state.getTrack(current).getQueued()) {
            state.removeTrack(current);
            return current;
        }

        return current + 1;
    }

    @Override
    public void endOfTrack() {
        LOGGER.trace("End of track. Proceeding with next.");
        handleNext();
    }

    @Override
    public void playbackReady() {
        if (state.getStatus() == Spirc.PlayStatus.kPlayStatusLoading) {
            state.setPositionMs(0);
            state.setPositionMeasuredAt(System.currentTimeMillis());
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            stateUpdated();
        }
    }

    @Override
    public void playbackError(@NotNull Exception ex) {
        LOGGER.fatal("Playback failed!", ex);
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
        public final boolean pauseWhenLoading;

        public Configuration() {
            this.preferredQuality = AudioQuality.VORBIS_160;
            this.normalisationPregain = 0;
            this.pauseWhenLoading = true;
        }
    }
}
