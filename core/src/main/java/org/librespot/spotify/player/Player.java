package org.librespot.spotify.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.librespot.spotify.core.Session;
import org.librespot.spotify.proto.Spirc;
import org.librespot.spotify.spirc.FrameListener;
import org.librespot.spotify.spirc.SpotifyIrc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * @author Gianlu
 */
public class Player implements FrameListener, TrackHandler.Listener {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final Session session;
    private final SpotifyIrc spirc;
    private final Spirc.State.Builder state;
    private final PlayerConfiguration conf;
    private final CacheManager cacheManager;
    private TrackHandler trackHandler;

    public Player(@NotNull PlayerConfiguration conf, @NotNull CacheManager.CacheConfiguration cacheConfiguration, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.spirc = session.spirc();
        this.state = initState();

        try {
            this.cacheManager = new CacheManager(cacheConfiguration);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

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
                    if (trackHandler != null) trackHandler.sendStop();
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
                handleSetVolume(frame.getVolume());
                break;
            case kMessageTypeVolumeDown:
                handleVolumeDown();
                break;
            case kMessageTypeVolumeUp:
                handleVolumeUp();
                break;
        }
    }

    private void handleSetVolume(int volume) {
        spirc.deviceState().setVolume(volume);

        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) controller.setVolume(volume);
        }

        stateUpdated();
    }

    private void handleVolumeDown() {
        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) controller.volumeDown();
            stateUpdated();
        }
    }

    private void handleVolumeUp() {
        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) controller.volumeUp();
            stateUpdated();
        }
    }

    private void stateUpdated() {
        spirc.deviceStateUpdated(state);
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
        if (trackHandler != null) trackHandler.sendSeek(pos);
        stateUpdated();
    }

    private void updatedTracks(@NotNull Spirc.Frame frame) {
        state.setPlayingTrackIndex(frame.getState().getPlayingTrackIndex());
        state.clearTrack();
        state.addAllTrack(frame.getState().getTrackList());
        state.setContextUri(frame.getState().getContextUri());
        state.setRepeat(frame.getState().getRepeat());
        state.setShuffle(frame.getState().getShuffle());
    }

    @Override
    public void finishedLoading(boolean play) {
        if (play) state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
        else state.setStatus(Spirc.PlayStatus.kPlayStatusPause);
        stateUpdated();
    }

    @Override
    public void loadingError(@NotNull Exception ex) {
        LOGGER.fatal("Failed loading track!", ex);
        state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
        stateUpdated();
    }

    @Override
    public void endOfTrack() {
        LOGGER.trace("End of track. Proceeding with next.");
        handleNext();
    }

    private void handleLoad(@NotNull Spirc.Frame frame) {
        if (!spirc.deviceState().getIsActive()) {
            spirc.deviceState()
                    .setIsActive(true)
                    .setBecameActiveAt(System.currentTimeMillis());
        }

        updatedTracks(frame);

        if (state.getTrackCount() > 0) {
            state.setPositionMs(frame.getState().getPositionMs());
            state.setPositionMeasuredAt(System.currentTimeMillis());

            loadTrack(frame.getState().getStatus() == Spirc.PlayStatus.kPlayStatusPlay);
        } else {
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
        }

        stateUpdated();
    }

    private void loadTrack(boolean play) {
        if (trackHandler != null) trackHandler.close();
        trackHandler = new TrackHandler(session, cacheManager, conf, this);
        trackHandler.sendLoad(state.getTrack(state.getPlayingTrackIndex()), play, state.getPositionMs());
        state.setStatus(Spirc.PlayStatus.kPlayStatusLoading);

        stateUpdated();
    }

    private void handlePlay() {
        if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPause) {
            if (trackHandler != null) trackHandler.sendPlay();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            state.setPositionMeasuredAt(System.currentTimeMillis());
            stateUpdated();
        }
    }

    private void handlePause() {
        if (state.getStatus() == Spirc.PlayStatus.kPlayStatusPlay) {
            if (trackHandler != null) trackHandler.sendPause();
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
        int newTrack = consumeQueuedTrack();
        boolean play = true;
        if (newTrack >= state.getTrackCount()) {
            newTrack = 0;
            play = state.getRepeat();
        }

        state.setPlayingTrackIndex(newTrack);
        state.setPositionMs(0);
        state.setPositionMeasuredAt(System.currentTimeMillis());

        loadTrack(play);
    }

    private void handlePrev() {
        if (getPosition() < 3000) {
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

            loadTrack(true);
        } else {
            state.setPositionMs(0);
            state.setPositionMeasuredAt(System.currentTimeMillis());
            if (trackHandler != null) trackHandler.sendSeek(0);
            stateUpdated();
        }
    }

    private int consumeQueuedTrack() {
        int current = state.getPlayingTrackIndex();
        if (state.getTrack(current).getQueued()) {
            state.removeTrack(current);
            return current;
        }

        return current + 1;
    }

    public interface PlayerConfiguration {
        @NotNull
        TrackHandler.AudioQuality preferredQuality();

        float normalisationPregain();
    }
}
