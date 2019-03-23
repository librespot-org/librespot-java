package xyz.gianlu.librespot.player;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.remote.Remote3Frame;
import xyz.gianlu.librespot.player.remote.Remote3Track;
import xyz.gianlu.librespot.player.tracks.PlaylistProvider;
import xyz.gianlu.librespot.player.tracks.StationProvider;
import xyz.gianlu.librespot.player.tracks.TracksProvider;
import xyz.gianlu.librespot.spirc.FrameListener;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * @author Gianlu
 */
public class Player implements FrameListener, TrackHandler.Listener, Closeable {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private static final JsonParser PARSER = new JsonParser();
    private final Session session;
    private final SpotifyIrc spirc;
    private final StateWrapper state;
    private final Configuration conf;
    private final LinesHolder lines;
    private TracksProvider tracksProvider;
    private TrackHandler trackHandler;
    private TrackHandler preloadTrackHandler;

    public Player(@NotNull Player.Configuration conf, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.spirc = session.spirc();
        this.state = new StateWrapper(initState());
        this.lines = new LinesHolder();

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

    private void handleFrame(@NotNull Spirc.MessageType type, @NotNull Spirc.Frame spircFrame, @Nullable Remote3Frame frame) {
        switch (type) {
            case kMessageTypeNotify:
                if (spirc.deviceState().getIsActive() && spircFrame.getDeviceState().getIsActive()) {
                    spirc.deviceState().setIsActive(false);
                    state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
                    if (trackHandler != null && !trackHandler.isStopped()) trackHandler.sendStop();
                    stateUpdated();

                    LOGGER.warn("Stopping player due to kMessageTypeNotify!");
                }
                break;
            case kMessageTypeLoad:
                if (frame == null)
                    break;

                if (frame.endpoint == Remote3Frame.Endpoint.Play) {
                    handleLoad(frame);
                } else if (frame.endpoint == Remote3Frame.Endpoint.SkipNext) {
                    handleSkipNext(frame);
                }
                break;
            case kMessageTypePlay:
                if (frame != null && (frame.endpoint == Remote3Frame.Endpoint.Play ||
                        frame.endpoint == Remote3Frame.Endpoint.Resume))
                    handlePlay();
                break;
            case kMessageTypePause:
                if (frame != null && frame.endpoint == Remote3Frame.Endpoint.Pause)
                    handlePause();
                break;
            case kMessageTypePlayPause:
                handlePlayPause();
                break;
            case kMessageTypeNext:
                if (frame != null && frame.endpoint == Remote3Frame.Endpoint.SkipNext)
                    handleNext();
                break;
            case kMessageTypePrev:
                if (frame != null && frame.endpoint == Remote3Frame.Endpoint.SkipPrev)
                    handlePrev();
                break;
            case kMessageTypeSeek:
                if (frame != null && frame.endpoint == Remote3Frame.Endpoint.SeekTo)
                    handleSeek(frame.value.getAsInt());
                break;
            case kMessageTypeReplace:
                if (frame != null && frame.endpoint == Remote3Frame.Endpoint.UpdateContext) {
                    updatedTracks(frame);
                    stateUpdated();
                }
                break;
            case kMessageTypeRepeat:
                if (frame != null && frame.endpoint == Remote3Frame.Endpoint.SetRepeatingContext) {
                    state.setRepeat(frame.value.getAsBoolean());
                    stateUpdated();
                }
                break;
            case kMessageTypeShuffle:
                if (frame != null && frame.endpoint == Remote3Frame.Endpoint.SetShufflingContext) {
                    state.setShuffle(frame.value.getAsBoolean());
                    handleShuffle();
                }
                break;
            case kMessageTypeVolume:
                handleSetVolume(spircFrame.getVolume());
                break;
            case kMessageTypeVolumeDown:
                handleVolumeDown();
                break;
            case kMessageTypeVolumeUp:
                handleVolumeUp();
                break;
            case kMessageTypeAction:
                if (frame == null) break;

                switch (frame.endpoint) {
                    case SetRepeatingTrack:
                        state.setRepeatingTrack(frame.value.getAsBoolean());
                        break;
                }
                break;
        }
    }

    private void handleSkipNext(@NotNull Remote3Frame frame) {
        if (frame.track == null) {
            LOGGER.fatal("Received invalid request, track is missing!");
            return;
        }

        state.seekTo(frame.track.uri);
        state.setPositionMs(0);
        state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());

        loadTrack(true);
    }

    @Override
    public void frame(@NotNull Spirc.Frame frame) {
        if (Objects.equals(frame.getIdent(), "play-token")) {
            LOGGER.debug(String.format("Skipping frame. {ident: %s}", frame.getIdent()));
            return;
        }

        try {
            String json = Utils.decodeGZip(frame.getContextPlayerState());
            if (!json.isEmpty()) LOGGER.trace("Frame has context_player_state: " + Utils.removeLineBreaks(json));
            handleFrame(frame.getTyp(), frame, json.isEmpty() ? null : new Remote3Frame(PARSER.parse(json).getAsJsonObject()));
        } catch (IOException | JsonSyntaxException ex) {
            LOGGER.warn(String.format("Failed parsing frame. {ident: %s}", frame.getIdent()), ex);
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
        int diff = (int) (TimeProvider.currentTimeMillis() - state.getPositionMeasuredAt());
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
        state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
        if (trackHandler != null) trackHandler.sendSeek(pos);
        stateUpdated();
    }

    private void updatedTracks(@NotNull Remote3Frame frame) {
        state.update(frame);

        String context = frame.context.uri;
        if (context.startsWith("spotify:station:") || context.startsWith("spotify:dailymix:"))
            tracksProvider = new StationProvider(session, state.state);
        else
            tracksProvider = new PlaylistProvider(session, state.state, conf);

        state.setRepeat(frame.options.playerOptionsOverride.repeatingContext);
        state.setShuffle(frame.options.playerOptionsOverride.shufflingContext);
        if (state.getShuffle() && conf.defaultUnshuffleBehaviour()) shuffleTracks();
    }

    @Override
    public void startedLoading(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            state.setStatus(Spirc.PlayStatus.kPlayStatusLoading);
            stateUpdated();
        }
    }

    @Override
    public void finishedLoading(@NotNull TrackHandler handler, int pos, boolean play) {
        if (handler == trackHandler) {
            if (play) state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            else state.setStatus(Spirc.PlayStatus.kPlayStatusPause);

            state.setPositionMs(pos);
            state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());

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
            if (state.isRepeatingTrack()) {
                state.setPositionMs(0);
                state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());

                LOGGER.trace("End of track. Repeating.");
                loadTrack(true);
            } else {
                LOGGER.trace("End of track. Proceeding with next.");
                handleNext();
            }
        }
    }

    @Override
    public void preloadNextTrack(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            int index = tracksProvider.getNextTrackIndex(false);
            if (index < state.getTrackCount()) {
                TrackId next = tracksProvider.getTrackAt(index);
                preloadTrackHandler = new TrackHandler(session, lines, conf, this);
                preloadTrackHandler.sendLoad(next, false, 0);
                LOGGER.trace("Started next track preload, gid: " + Utils.bytesToHex(next.getGid()));
            }
        }
    }

    private void handleLoad(@NotNull Remote3Frame frame) {
        if (!spirc.deviceState().getIsActive()) {
            spirc.deviceState()
                    .setIsActive(true)
                    .setBecameActiveAt(TimeProvider.currentTimeMillis());
        }

        LOGGER.debug(String.format("Loading context, uri: %s", frame.context.uri));

        updatedTracks(frame);

        if (state.getTrackCount() > 0) {
            state.setPositionMs(frame.options.seekTo);
            state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
            loadTrack(!frame.options.initiallyPaused);
        } else {
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
            stateUpdated();
        }
    }

    private void loadTrack(boolean play) {
        if (trackHandler != null) trackHandler.close();

        TrackId id = tracksProvider.getCurrentTrack();
        if (preloadTrackHandler != null && preloadTrackHandler.isTrack(id)) {
            trackHandler = preloadTrackHandler;
            preloadTrackHandler = null;
            trackHandler.sendSeek(state.getPositionMs());
        } else {
            trackHandler = new TrackHandler(session, lines, conf, this);
            trackHandler.sendLoad(id, play, state.getPositionMs());
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
            state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
            stateUpdated();
        }
    }

    private void handlePause() {
        if (state.isStatus(Spirc.PlayStatus.kPlayStatusPlay)) {
            if (trackHandler != null) trackHandler.sendPause();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPause);

            long now = TimeProvider.currentTimeMillis();
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
            if (state.getRepeat()) {
                newTrack = 0;
                play = true;
            } else {
                if (conf.autoplayEnabled()) {
                    loadAutoplay();
                    return;
                } else {
                    newTrack = 0;
                    play = false;
                }
            }
        }

        state.setPlayingTrackIndex(newTrack);
        state.setPositionMs(0);
        state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());

        loadTrack(play);
    }

    private void loadAutoplay() {
        String context = state.getContextUri();
        if (context == null) {
            LOGGER.fatal("Cannot load autoplay with null context!");
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
            stateUpdated();
            return;
        }

        try {
            MercuryRequests.StationsWrapper json = session.mercury().sendSync(MercuryRequests.getStationFor(context));
            state.update(json);

            state.setPositionMs(0);
            state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());

            tracksProvider = new StationProvider(session, state.state);
            loadTrack(true);

            LOGGER.debug(String.format("Loading context for autoplay, uri: %s", json.uri()));
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading autoplay station!", ex);
            state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
            stateUpdated();
        }
    }

    private void handlePrev() {
        if (getPosition() < 3000) {
            state.setPlayingTrackIndex(tracksProvider.getPrevTrackIndex(true));
            state.setPositionMs(0);
            state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());

            loadTrack(true);
        } else {
            state.setPositionMs(0);
            state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
            if (trackHandler != null) trackHandler.sendSeek(0);
            stateUpdated();
        }
    }

    @Override
    public void close() {
        if (trackHandler != null) {
            trackHandler.close();
            trackHandler = null;
        }

        if (preloadTrackHandler != null) {
            preloadTrackHandler.close();
            preloadTrackHandler = null;
        }
    }

    public interface Configuration {
        @NotNull
        StreamFeeder.AudioQuality preferredQuality();

        boolean preloadEnabled();

        float normalisationPregain();

        boolean defaultUnshuffleBehaviour();

        @Nullable
        String[] mixerSearchKeywords();

        boolean logAvailableMixers();

        int initialVolume();

        boolean autoplayEnabled();

        boolean useCdn();
    }

    private class StateWrapper {
        private final Spirc.State.Builder state;
        private boolean repeatingTrack = false;

        StateWrapper(@NotNull Spirc.State.Builder state) {
            this.state = state;
        }

        boolean isRepeatingTrack() {
            return repeatingTrack;
        }

        void setRepeatingTrack(boolean repeatingTrack) {
            this.repeatingTrack = repeatingTrack;
        }

        @Nullable
        String getContextUri() {
            return state.getContextUri();
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

        void seekTo(@Nullable String uri) {
            int pos = -1;
            List<Spirc.TrackRef> tracks = state.getTrackList();
            for (int i = 0; i < tracks.size(); i++) {
                Spirc.TrackRef track = tracks.get(i);
                if (track.getUri().equals(uri)) {
                    pos = i;
                    break;
                }
            }

            if (pos == -1)
                pos = 0;

            state.setPlayingTrackIndex(pos);
        }

        void update(@NotNull Remote3Frame frame) {
            if (frame.context == null)
                throw new IllegalArgumentException("Invalid frame received!");

            state.setContextUri(frame.context.uri);
            state.clearTrack();

            if (frame.context.pages != null) {
                String trackUid = null;
                int pageIndex = -1;
                if (frame.options != null && frame.options.skipTo != null) {
                    trackUid = frame.options.skipTo.trackUid;
                    pageIndex = frame.options.skipTo.pageIndex;
                }

                if (pageIndex == -1) pageIndex = 0;

                int index = -1;
                List<Remote3Track> tracks = frame.context.pages.get(pageIndex).tracks;
                for (int i = 0; i < tracks.size(); i++) {
                    Remote3Track track = tracks.get(i);
                    state.addTrack(track.toTrackRef());

                    if (Objects.equals(trackUid, track.uri) || Objects.equals(trackUid, track.uid))
                        index = i;
                }

                if (index == -1)
                    index = 0;

                state.setPlayingTrackIndex(index);
            } else {
                List<Remote3Track> tracks;
                try {
                    MercuryRequests.ResolvedContextWrapper context = session.mercury().sendSync(MercuryRequests.resolveContext(frame.context.uri));
                    tracks = context.pages().get(0).tracks;
                } catch (IOException | MercuryClient.MercuryException ex) {
                    LOGGER.fatal("Failed resolving context: " + frame.context.uri, ex);
                    return;
                }

                for (Remote3Track track : tracks) state.addTrack(track.toTrackRef());

                if (frame.options != null && frame.options.skipTo != null)
                    state.setPlayingTrackIndex(frame.options.skipTo.trackIndex);
                else
                    state.setPlayingTrackIndex(0);
            }

            if (frame.options != null && frame.options.playerOptionsOverride != null) {
                state.setRepeat(frame.options.playerOptionsOverride.repeatingContext);
                state.setShuffle(frame.options.playerOptionsOverride.shufflingContext);
            }
        }

        void update(@NotNull MercuryRequests.StationsWrapper json) {
            state.setContextUri(json.uri());

            state.setPlayingTrackIndex(0);
            state.clearTrack();
            state.addAllTrack(json.tracks());
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
