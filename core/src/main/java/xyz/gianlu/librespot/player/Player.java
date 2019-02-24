package xyz.gianlu.librespot.player;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.tracks.PlaylistProvider;
import xyz.gianlu.librespot.player.tracks.StationProvider;
import xyz.gianlu.librespot.player.tracks.TracksProvider;
import xyz.gianlu.librespot.spirc.FrameListener;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

/**
 * @author Gianlu
 */
public class Player implements FrameListener, TrackHandler.Listener, Closeable {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final Session session;
    private final SpotifyIrc spirc;
    private final StateWrapper state;
    private final Configuration conf;
    private final CacheManager cacheManager;
    private final LinesHolder lines;
    private TracksProvider tracksProvider;
    private TrackHandler trackHandler;
    private TrackHandler preloadTrackHandler;

    public Player(@NotNull Player.Configuration conf, @NotNull CacheManager.CacheConfiguration cacheConfiguration, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.spirc = session.spirc();
        this.state = new StateWrapper(initState());
        this.lines = new LinesHolder();

        try {
            this.cacheManager = new CacheManager(cacheConfiguration);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        spirc.addListener(this);
    }

    public void playPause() {
        handlePlayPause();
    }

    public void play() {
        handlePlay();
    }

    public void pause() {
        handlePause();
    }

    public void next() {
        handleNext();
    }

    public void previous() {
        handlePrev();
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
                    if (trackHandler != null && !trackHandler.isStopped()) trackHandler.sendStop();
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
                if (!Objects.equals(frame.getIdent(), "play-token"))
                    handlePause();
                break;
            case kMessageTypePlayPause:
                handlePlayPause();
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

    private void handlePlayPause() {
        if (state.isStatus(Spirc.PlayStatus.kPlayStatusPlay)) handlePause();
        else if (state.isStatus(Spirc.PlayStatus.kPlayStatusPause)) handlePlay();
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
            if (controller != null) spirc.deviceState().setVolume(controller.volumeDown());
            stateUpdated();
        }
    }

    private void handleVolumeUp() {
        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) spirc.deviceState().setVolume(controller.volumeUp());
            stateUpdated();
        }
    }

    private void stateUpdated() {
        spirc.deviceStateUpdated(state.state);
    }

    private int getPosition() {
        int diff = (int) (System.currentTimeMillis() - state.getPositionMeasuredAt());
        return state.getPositionMs() + diff;
    }

    private void handleShuffle() {
        if (state.getShuffle()) shuffleTracks();
        else unshuffleTracks();
        stateUpdated();
    }

    private void shuffleTracks() {
        if (tracksProvider instanceof PlaylistProvider)
            ((PlaylistProvider) tracksProvider).shuffleTracks(session.random());
        else
            LOGGER.warn("Cannot shuffle TracksProvider: " + tracksProvider);
    }

    private void unshuffleTracks() {
        if (tracksProvider instanceof PlaylistProvider)
            ((PlaylistProvider) tracksProvider).unshuffleTracks();
        else
            LOGGER.warn("Cannot unshuffle TracksProvider: " + tracksProvider);
    }

    private void handleSeek(int pos) {
        state.setPositionMs(pos);
        state.setPositionMeasuredAt(System.currentTimeMillis());
        if (trackHandler != null) trackHandler.sendSeek(pos);
        stateUpdated();
    }

    private void updatedTracks(@NotNull Spirc.Frame frame) {
        state.update(frame);
        String context = frame.getState().getContextUri();

        if (context.startsWith("spotify:station:")) tracksProvider = new StationProvider(session, state.state, frame);
        else tracksProvider = new PlaylistProvider(session, state.state, frame, conf);

        state.setRepeat(frame.getState().getRepeat());
        state.setShuffle(frame.getState().getShuffle());
        if (state.getShuffle() && conf.defaultUnshuffleBehaviour()) shuffleTracks();
    }

    @Override
    public void finishedLoading(@NotNull TrackHandler handler, boolean play) {
        if (handler == trackHandler) {
            if (play) state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            else state.setStatus(Spirc.PlayStatus.kPlayStatusPause);
            stateUpdated();
        } else if (handler == preloadTrackHandler) {
            LOGGER.trace("Preloaded track is ready.");
        }
    }

    @Override
    public void loadingError(@NotNull TrackHandler handler, @NotNull TrackId id, @NotNull Exception ex) {
        if (handler == trackHandler) {
            LOGGER.fatal(String.format("Failed loading track, gid: %s", Utils.bytesToHex(id.getGid())), ex);
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
            stateUpdated();
        } else if (handler == preloadTrackHandler) {
            LOGGER.warn("Preloaded track loading failed!", ex);
            preloadTrackHandler = null;
        }
    }

    @Override
    public void endOfTrack(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            LOGGER.trace("End of track. Proceeding with next.");
            handleNext();
        }
    }

    @Override
    public void preloadNextTrack(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            int index = tracksProvider.getNextTrackIndex(false);
            if (index < state.getTrackCount()) {
                TrackId next = tracksProvider.getTrackAt(index);
                preloadTrackHandler = new TrackHandler(session, lines, cacheManager, conf, this);
                preloadTrackHandler.sendLoad(next, false, 0);
                LOGGER.trace("Started next track preload, gid: " + Utils.bytesToHex(next.getGid()));
            }
        }
    }

    private void handleLoad(@NotNull Spirc.Frame frame) {
        if (!spirc.deviceState().getIsActive()) {
            spirc.deviceState()
                    .setIsActive(true)
                    .setBecameActiveAt(System.currentTimeMillis());
        }

        LOGGER.debug(String.format("Loading context, uri: %s", frame.getState().getContextUri()));

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

        TrackId id = tracksProvider.getCurrentTrack();
        if (preloadTrackHandler != null && preloadTrackHandler.isTrack(id)) {
            trackHandler = preloadTrackHandler;
            preloadTrackHandler = null;
            trackHandler.sendSeek(state.getPositionMs());
        } else {
            trackHandler = new TrackHandler(session, lines, cacheManager, conf, this);
            trackHandler.sendLoad(id, play, state.getPositionMs());
            state.setStatus(Spirc.PlayStatus.kPlayStatusLoading);
        }

        if (play) {
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            trackHandler.sendPlay();
        } else {
            state.setStatus(Spirc.PlayStatus.kPlayStatusPause);
        }

        stateUpdated();
    }

    private void handlePlay() {
        if (state.isStatus(Spirc.PlayStatus.kPlayStatusPause)) {
            if (trackHandler != null) trackHandler.sendPlay();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            state.setPositionMeasuredAt(System.currentTimeMillis());
            stateUpdated();
        }
    }

    private void handlePause() {
        if (state.isStatus(Spirc.PlayStatus.kPlayStatusPlay)) {
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
        int newTrack = tracksProvider.getNextTrackIndex(true);
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
            state.setPlayingTrackIndex(tracksProvider.getPrevTrackIndex(true));
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

    @Override
    public void close() throws IOException {
        if (trackHandler != null) {
            trackHandler.close();
            trackHandler = null;
        }

        if (preloadTrackHandler != null) {
            preloadTrackHandler.close();
            preloadTrackHandler = null;
        }

        cacheManager.close();
    }

    public interface Configuration {
        @NotNull
        StreamFeeder.AudioQuality preferredQuality();

        boolean preloadEnabled();

        float normalisationPregain();

        boolean defaultUnshuffleBehaviour();

        @NotNull
        String[] mixerSearchKeywords();

        boolean logAvailableMixers();

        int initialVolume();
    }

    private class StateWrapper {
        private final Spirc.State.Builder state;

        StateWrapper(@NotNull Spirc.State.Builder state) {
            this.state = state;
        }

        @NotNull
        Spirc.PlayStatus getStatus() {
            return state.getStatus();
        }

        void setStatus(@NotNull Spirc.PlayStatus status) {
            state.setStatus(status);
        }

        boolean isStatus(@NotNull Spirc.PlayStatus status) {
            return status == getStatus();
        }

        boolean getShuffle() {
            return state.getShuffle();
        }

        void setShuffle(boolean shuffle) {
            state.setShuffle(shuffle && (tracksProvider == null || tracksProvider.canShuffle()));
        }

        void update(@NotNull Spirc.Frame frame) {
            state.setContextUri(frame.getState().getContextUri());
        }

        long getPositionMeasuredAt() {
            return state.getPositionMeasuredAt();
        }

        void setPositionMeasuredAt(long ms) {
            state.setPositionMeasuredAt(ms);
        }

        int getPositionMs() {
            return state.getPositionMs();
        }

        void setPositionMs(int pos) {
            state.setPositionMs(pos);
        }

        boolean getRepeat() {
            return state.getRepeat();
        }

        void setRepeat(boolean repeat) {
            state.setRepeat(repeat && (tracksProvider == null || tracksProvider.canRepeat()));
        }

        void setPlayingTrackIndex(int i) {
            state.setPlayingTrackIndex(i);
        }

        int getTrackCount() {
            return state.getTrackCount();
        }
    }
}
