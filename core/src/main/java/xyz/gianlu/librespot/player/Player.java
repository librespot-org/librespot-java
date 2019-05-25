package xyz.gianlu.librespot.player;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.core.TimeProvider;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.codecs.AudioQuality;
import xyz.gianlu.librespot.player.contexts.SpotifyContext;
import xyz.gianlu.librespot.player.remote.Remote3Frame;
import xyz.gianlu.librespot.player.remote.Remote3Page;
import xyz.gianlu.librespot.player.remote.Remote3Track;
import xyz.gianlu.librespot.player.tracks.PlaylistProvider;
import xyz.gianlu.librespot.player.tracks.StationProvider;
import xyz.gianlu.librespot.player.tracks.TracksProvider;
import xyz.gianlu.librespot.spirc.FrameListener;
import xyz.gianlu.librespot.spirc.SpotifyIrc;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
                if (frame == null) break;

                if (frame.endpoint == Remote3Frame.Endpoint.UpdateContext) {
                    try {
                        state.updateContext(frame.context);
                        stateUpdated();
                    } catch (SpotifyContext.UnsupportedContextException ex) {
                        LOGGER.fatal("Cannot play local tracks!", ex);
                        panicState();
                        return;
                    }
                } else if (frame.endpoint == Remote3Frame.Endpoint.SetQueue) {
                    state.setQueue(frame);
                    stateUpdated();
                } else if (frame.endpoint == Remote3Frame.Endpoint.AddToQueue) {
                    state.addToQueue(frame);
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
        if (state.getShuffle()) shuffleTracks(false);
        else unshuffleTracks();
        stateUpdated();
    }

    private void shuffleTracks(boolean fully) {
        if (tracksProvider == null) return;

        if (tracksProvider.canShuffle() && tracksProvider instanceof PlaylistProvider)
            ((PlaylistProvider) tracksProvider).shuffleTracks(session.random(), fully);
        else
            LOGGER.warn("Cannot shuffle TracksProvider: " + tracksProvider);
    }

    private void unshuffleTracks() {
        if (tracksProvider == null) return;

        if (tracksProvider.canShuffle() && tracksProvider instanceof PlaylistProvider)
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

    private void loadTracksProvider(@NotNull String uri) throws SpotifyContext.UnsupportedContextException {
        SpotifyContext context = SpotifyContext.from(uri);
        tracksProvider = context.initProvider(session, state.state, conf);
    }

    @Override
    public void startedLoading(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            if (conf.enableLoadingState()) {
                state.setStatus(Spirc.PlayStatus.kPlayStatusLoading);
                stateUpdated();
            }
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
    public void loadingError(@NotNull TrackHandler handler, @NotNull PlayableId id, @NotNull Exception ex) {
        if (handler == trackHandler) {
            if (ex instanceof ContentRestrictedException) {
                LOGGER.fatal(String.format("Can't load track (content restricted), gid: %s", Utils.bytesToHex(id.getGid())), ex);
                handleNext();
                return;
            }

            LOGGER.fatal(String.format("Failed loading track, gid: %s", Utils.bytesToHex(id.getGid())), ex);
            panicState();
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
                PlayableId next = tracksProvider.getTrackAt(index);
                preloadTrackHandler = new TrackHandler(session, lines, conf, this);
                preloadTrackHandler.sendLoad(next, false, 0);
                LOGGER.trace("Started next track preload, gid: " + Utils.bytesToHex(next.getGid()));
            }
        }
    }

    @Override
    public void playbackError(@NotNull TrackHandler handler, @NotNull Exception ex) {
        if (handler == trackHandler) {
            if (ex instanceof AbsChunckedInputStream.ChunkException)
                LOGGER.fatal("Failed retrieving chunk, playback failed!", ex);
            else
                LOGGER.fatal("Playback error!", ex);

            panicState();
        } else if (handler == preloadTrackHandler) {
            LOGGER.warn("Preloaded track loading failed!", ex);
            preloadTrackHandler = null;
        }
    }

    @Override
    public void playbackHalted(@NotNull TrackHandler handler, int chunk) {
        if (handler == trackHandler) {
            LOGGER.debug(String.format("Playback halted on retrieving chunk %d.", chunk));

            if (conf.enableLoadingState()) {
                state.setStatus(Spirc.PlayStatus.kPlayStatusLoading);
                stateUpdated();
            }
        }
    }

    @Override
    public void playbackResumedFromHalt(@NotNull TrackHandler handler, int chunk, long diff) {
        if (handler == trackHandler) {
            LOGGER.debug(String.format("Playback resumed, chunk %d retrieved, took %dms.", chunk, diff));

            long now = TimeProvider.currentTimeMillis();
            state.setPositionMs(state.getPositionMs() + (int) (now - state.getPositionMeasuredAt() - diff));
            state.setPositionMeasuredAt(now);
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            stateUpdated();
        }
    }

    private void panicState() {
        state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
        stateUpdated();
    }

    private void handleLoad(@NotNull Remote3Frame frame) {
        if (!spirc.deviceState().getIsActive()) {
            spirc.deviceState()
                    .setIsActive(true)
                    .setBecameActiveAt(TimeProvider.currentTimeMillis());
        }

        LOGGER.debug(String.format("Loading context, uri: %s", frame.context.uri));

        try {
            state.load(frame);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState();
            return;
        } catch (SpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState();
            return;
        }

        if (state.getTrackCount() > 0) {
            state.setPositionMs(frame.options.seekTo == -1 ? 0 : frame.options.seekTo);
            state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
            loadTrack(!frame.options.initiallyPaused);
        } else {
            panicState();
        }
    }

    private void loadTrack(boolean play) {
        if (trackHandler != null) trackHandler.close();

        PlayableId id = tracksProvider.getCurrentTrack();
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
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            if (trackHandler != null) {
                trackHandler.sendPlay();
                state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
            }

            stateUpdated();
        }
    }

    private void handlePause() {
        if (state.isStatus(Spirc.PlayStatus.kPlayStatusPlay)) {
            if (trackHandler != null) trackHandler.sendPause();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPause);

            long now = TimeProvider.currentTimeMillis();
            state.setPositionMs(state.getPositionMs() + (int) (now - state.getPositionMeasuredAt()));
            state.setPositionMeasuredAt(now);
            stateUpdated();
        }
    }

    private void handleNext() {
        if (tracksProvider == null) return;

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
            panicState();
            return;
        }

        try {
            MercuryClient.Response resp = session.mercury().sendSync(MercuryRequests.autoplayQuery(context));
            if (resp.statusCode == 200) {
                String newContext = resp.payload.readIntoString(0);
                state.loadFromUri(newContext);

                state.setPositionMs(0);
                state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());

                tracksProvider = new StationProvider(session, state.state);
                loadTrack(true);

                LOGGER.debug(String.format("Loading context for autoplay, uri: %s", newContext));
            } else if (resp.statusCode == 204) {
                MercuryRequests.StationsWrapper station = session.mercury().sendSync(MercuryRequests.getStationFor(context));
                state.loadStation(station);

                tracksProvider = new StationProvider(session, state.state);
                loadTrack(true);

                LOGGER.debug(String.format("Loading context for autoplay (using radio-apollo), uri: %s", state.getContextUri()));
            } else {
                LOGGER.fatal("Failed retrieving autoplay context, code: " + resp.statusCode);
                panicState();
            }
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading autoplay station!", ex);
            panicState();
        } catch (SpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState();
        }
    }

    private void handlePrev() {
        if (tracksProvider == null) return;

        if (getPosition() < 3000) {
            state.setPlayingTrackIndex(tracksProvider.getPrevTrackIndex());
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

    @Nullable
    public Metadata.Track currentTrack() {
        return trackHandler.track();
    }

    @Nullable
    public Metadata.Episode currentEpisode() {
        return trackHandler.episode();
    }

    @Nullable
    public PlayableId currentPlayableId() {
        return tracksProvider == null ? null : tracksProvider.getCurrentTrack();
    }

    public interface Configuration {
        @NotNull
        AudioQuality preferredQuality();

        boolean preloadEnabled();

        float normalisationPregain();

        boolean defaultUnshuffleBehaviour();

        @Nullable
        String[] mixerSearchKeywords();

        boolean logAvailableMixers();

        int initialVolume();

        boolean autoplayEnabled();

        boolean useCdnForTracks();

        boolean useCdnForEpisodes();

        boolean enableLoadingState();
    }

    private static class TrackSelector {
        private final String trackUid;
        private final String trackUri;
        private int trackIndex;
        private int selectedIndex = -1;

        TrackSelector(@Nullable Remote3Frame.Options.SkipTo skipTo) {
            if (skipTo == null) {
                trackUid = null;
                trackUri = null;
                trackIndex = -1;
            } else {
                trackUri = skipTo.trackUri;
                if (skipTo.trackUid != null) {
                    trackUid = skipTo.trackUid;
                    trackIndex = -1;
                } else {
                    trackIndex = skipTo.trackIndex;
                    trackUid = null;
                }
            }
        }

        TrackSelector(@NotNull String context, @NotNull Spirc.TrackRef ref) throws SpotifyContext.UnsupportedContextException {
            trackUid = null;
            trackIndex = -1;

            PlayableId id = SpotifyContext.from(context).createId(ref);
            trackUri = id.toSpotifyUri();
        }

        void inspect(int index, @NotNull Remote3Track track) {
            if (findMatch()) return;

            if (trackUid == null && trackUri == null) return;

            if (trackUid != null) {
                if (Objects.equals(track.uid, trackUid))
                    selectedIndex = index;
            } else {
                if (Objects.equals(track.uri, trackUri))
                    selectedIndex = index;
            }
        }

        boolean findMatch() {
            return selectedIndex != -1 || trackIndex != -1;
        }

        int playingIndex() {
            if (trackIndex != -1) return trackIndex;
            if (trackUid == null && trackUri == null) return 0;
            return selectedIndex == -1 ? 0 : selectedIndex;
        }
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

        void loadStation(@NotNull MercuryRequests.StationsWrapper station) {
            state.setContextUri(station.uri());

            state.setPlayingTrackIndex(0);
            state.clearTrack();
            state.addAllTrack(station.tracks());
        }

        int lastQueuedSongIndex() {
            int lastQueued = -1;
            int firstQueued = -1;
            for (int i = state.getPlayingTrackIndex(); i < state.getTrackCount(); i++) {
                if (state.getTrack(i).getQueued()) {
                    if (firstQueued == -1) firstQueued = i;
                } else {
                    if (firstQueued != -1 && lastQueued == -1) lastQueued = i - 1;
                }
            }

            return lastQueued;
        }

        @NotNull
        private List<Remote3Page> getPages(@NotNull Remote3Frame.Context context) throws IOException, MercuryClient.MercuryException {
            MercuryRequests.ResolvedContextWrapper resolved = session.mercury().sendSync(MercuryRequests.resolveContext(context.uri));
            return resolved.pages();
        }

        @NotNull
        private List<Remote3Track> getTracks(@NotNull String pageUrl) throws IOException {
            MercuryClient.Response resp = session.mercury().sendSync(RawMercuryRequest.newBuilder()
                    .setUri(pageUrl).setMethod("GET").build());

            JsonObject obj = new JsonParser().parse(new InputStreamReader(resp.payload.stream())).getAsJsonObject();
            return Remote3Track.array(obj.getAsJsonArray("tracks"));
        }

        void loadFromUri(@NotNull String context) throws IOException, MercuryClient.MercuryException, SpotifyContext.UnsupportedContextException {
            state.setContextUri(context);
            state.clearTrack();

            MercuryRequests.ResolvedContextWrapper resolved = session.mercury().sendSync(MercuryRequests.resolveContext(context));
            loadPage(resolved.pages().get(0), null);
            loadTracksProvider(context);
        }

        private void loadPage(@NotNull Remote3Page page, @Nullable TrackSelector selector) throws IOException {
            List<Remote3Track> tracks = page.tracks;
            if (tracks == null) {
                if (page.pageUrl != null) tracks = getTracks(page.pageUrl);
                else throw new IllegalStateException("How do I load this page?!");
            }

            int updated = PlayableId.removeUnsupported(tracks, selector == null ? -1 : selector.trackIndex);
            if (selector != null && updated != -1) selector.trackIndex = updated;

            for (int i = 0; i < tracks.size(); i++) {
                Remote3Track track = tracks.get(i);
                state.addTrack(track.toTrackRef());
                if (selector != null) selector.inspect(i, track);
            }

            state.setPlayingTrackIndex(selector == null ? 0 : selector.playingIndex());

            shuffleTracks((selector == null || !selector.findMatch()) && state.getShuffle());
        }

        void load(@NotNull Remote3Frame frame) throws IOException, MercuryClient.MercuryException, SpotifyContext.UnsupportedContextException {
            if (frame.context == null) throw new IllegalArgumentException("Missing context object!");

            if (frame.options != null && frame.options.playerOptionsOverride != null) {
                Optional.ofNullable(frame.options.playerOptionsOverride.repeatingContext).ifPresent(state::setRepeat);
                Optional.ofNullable(frame.options.playerOptionsOverride.shufflingContext).ifPresent(state::setShuffle);
                Optional.ofNullable(frame.options.playerOptionsOverride.repeatingTrack).ifPresent(this::setRepeatingTrack);
            }

            if (frame.context.uri == null) {
                state.clearTrack();
                state.setPlayingTrackIndex(0);
                return;
            }

            JsonObject metadata = frame.context.metadata;
            if (metadata == null) {
                MercuryRequests.ResolvedContextWrapper resolved = session.mercury().sendSync(MercuryRequests.resolveContext(frame.context.uri));
                metadata = resolved.metadata();
            }

            JsonElement elm = metadata.get("context_description");
            if (elm != null) state.setContextDescription(elm.getAsString());
            else state.setContextDescription("");

            state.setContextUri(frame.context.uri);
            state.clearTrack();

            int pageIndex;
            if (frame.options == null || frame.options.skipTo == null) {
                pageIndex = 0;
            } else {
                pageIndex = frame.options.skipTo.pageIndex;
                if (pageIndex == -1) pageIndex = 0;
            }

            List<Remote3Page> pages = frame.context.pages;
            if (pages == null) pages = getPages(frame.context);

            Remote3Page page = pages.get(pageIndex);
            loadPage(page, new TrackSelector(frame.options == null ? null : frame.options.skipTo));
            loadTracksProvider(frame.context.uri);
        }

        void updateContext(@NotNull Remote3Frame.Context context) throws SpotifyContext.UnsupportedContextException {
            Spirc.TrackRef previouslyPlaying = state.getTrack(state.getPlayingTrackIndex());

            state.clearTrack();

            Remote3Page page;
            List<Remote3Track> tracks;
            if (context.pages == null || context.pages.isEmpty() || (page = context.pages.get(0)) == null || (tracks = page.tracks) == null) {
                LOGGER.warn("Did not update context. Malformed request.");
                return;
            }

            PlayableId.removeUnsupported(tracks, -1);

            TrackSelector selector = new TrackSelector(context.uri, previouslyPlaying);
            for (int i = 0; i < tracks.size(); i++) {
                Remote3Track track = tracks.get(i);
                state.addTrack(track.toTrackRef());
                selector.inspect(i, track);
            }

            state.setPlayingTrackIndex(selector.playingIndex());

            if (page.nextPageUrl != null && tracksProvider instanceof StationProvider)
                ((StationProvider) tracksProvider).knowsNextPageUrl(page.nextPageUrl);
        }

        void setQueue(@NotNull Remote3Frame frame) {
            Spirc.TrackRef currentlyPlaying = state.getTrack(state.getPlayingTrackIndex());

            state.clearTrack();

            List<Remote3Track> prevTracks = frame.prevTracks;
            if (prevTracks != null) {
                PlayableId.removeUnsupported(prevTracks, -1);
                for (Remote3Track track : prevTracks)
                    state.addTrack(track.toTrackRef());
            }

            state.addTrack(currentlyPlaying);
            int index = state.getTrackCount() - 1;

            List<Remote3Track> nextTracks = frame.nextTracks;
            if (nextTracks != null) {
                PlayableId.removeUnsupported(nextTracks, -1);
                for (Remote3Track track : nextTracks)
                    state.addTrack(track.toTrackRef());
            }

            state.setPlayingTrackIndex(index);
        }

        void addToQueue(@NotNull Remote3Frame frame) {
            if (frame.track == null)
                throw new IllegalArgumentException("Missing track object!");

            int index = lastQueuedSongIndex();
            if (index == -1) index = state.getPlayingTrackIndex() + 1;

            state.addTrack(index, frame.track.toTrackRef());
        }
    }
}
