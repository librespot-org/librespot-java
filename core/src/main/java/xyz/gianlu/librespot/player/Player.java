package xyz.gianlu.librespot.player;

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
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.codecs.AudioQuality;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;
import xyz.gianlu.librespot.player.remote.Remote3Frame;
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
    private static final JsonParser PARSER = new JsonParser();
    private final Session session;
    private final SpotifyIrc spirc;
    private final StateWrapper state;
    private final Configuration conf;
    private final LinesHolder lines;
    private TrackHandler trackHandler;
    private TrackHandler preloadTrackHandler;

    public Player(@NotNull Player.Configuration conf, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.spirc = session.spirc();
        this.state = new StateWrapper(session);
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

    private void handleFrame(@NotNull Spirc.MessageType type, @NotNull Spirc.Frame spircFrame, @Nullable Remote3Frame frame) {
        switch (type) {
            case kMessageTypeNotify:
                if (spirc.deviceState().getIsActive() && spircFrame.getDeviceState().getIsActive()) {
                    spirc.deviceState().setIsActive(false);
                    state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
                    if (trackHandler != null && !trackHandler.isStopped()) trackHandler.sendStop();
                    state.updated();

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
                    state.updateContext(frame.context);
                    state.updated();
                } else if (frame.endpoint == Remote3Frame.Endpoint.SetQueue) {
                    state.setQueue(frame);
                    state.updated();
                } else if (frame.endpoint == Remote3Frame.Endpoint.AddToQueue) {
                    state.addToQueue(frame);
                    state.updated();
                }
                break;
            case kMessageTypeRepeat:
                if (frame != null && frame.endpoint == Remote3Frame.Endpoint.SetRepeatingContext) {
                    state.setRepeat(frame.value.getAsBoolean());
                    state.updated();
                }
                break;
            case kMessageTypeShuffle:
                if (frame != null && frame.endpoint == Remote3Frame.Endpoint.SetShufflingContext) {
                    state.setShuffle(frame.value.getAsBoolean());
                    state.updated();
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

                if (frame.endpoint == Remote3Frame.Endpoint.SetRepeatingTrack) {
                    state.setRepeatingTrack(frame.value.getAsBoolean());
                    state.updated();
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

        String json;
        try {
            json = Utils.decodeGZip(frame.getContextPlayerState());
            if (!json.isEmpty()) LOGGER.trace("Frame has context_player_state: " + Utils.removeLineBreaks(json));
        } catch (IOException | JsonSyntaxException ex) {
            LOGGER.warn(String.format("Failed parsing frame. {ident: %s, seq: %d}", frame.getIdent(), frame.getSeqNr()), ex);
            return;
        }

        handleFrame(frame.getTyp(), frame, json.isEmpty() ? null : new Remote3Frame(PARSER.parse(json).getAsJsonObject()));
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

        state.updated();
    }

    private void handleVolumeDown() {
        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) spirc.deviceState().setVolume(controller.volumeDown());
            state.updated();
        }
    }

    private void handleVolumeUp() {
        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) spirc.deviceState().setVolume(controller.volumeUp());
            state.updated();
        }
    }

    private int getPosition() {
        int diff = (int) (TimeProvider.currentTimeMillis() - state.getPositionMeasuredAt());
        return state.getPositionMs() + diff;
    }

    private void handleSeek(int pos) {
        state.setPositionMs(pos);
        state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
        if (trackHandler != null) trackHandler.sendSeek(pos);
        state.updated();
    }

    @Override
    public void startedLoading(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            if (conf.enableLoadingState()) {
                state.setStatus(Spirc.PlayStatus.kPlayStatusLoading);
                state.updated();
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

            state.updated();
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
        if (handler == trackHandler && state.hasTracks()) {
            PlayableId next = state.nextPlayableDoNotSet();
            if (next != null) {
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
                state.updated();
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
            state.updated();
        }
    }

    private void panicState() {
        if (trackHandler != null) trackHandler.sendStop();
        state.setStatus(Spirc.PlayStatus.kPlayStatusStop);
        state.updated();
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
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState();
            return;
        }

        state.setPositionMs(frame.options.seekTo == -1 ? 0 : frame.options.seekTo);
        state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());

        boolean play;
        if (frame.options.initiallyPaused != null) play = !frame.options.initiallyPaused;
        else play = true;
        loadTrack(play);
    }

    private void loadTrack(boolean play) {
        if (trackHandler != null) trackHandler.close();

        PlayableId id = state.getCurrentTrack();
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

        state.updated();
    }

    private void handlePlay() {
        if (state.isStatus(Spirc.PlayStatus.kPlayStatusPause)) {
            state.setStatus(Spirc.PlayStatus.kPlayStatusPlay);
            if (trackHandler != null) {
                trackHandler.sendPlay();
                state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
            }

            state.updated();
        }
    }

    private void handlePause() {
        if (state.isStatus(Spirc.PlayStatus.kPlayStatusPlay)) {
            if (trackHandler != null) trackHandler.sendPause();
            state.setStatus(Spirc.PlayStatus.kPlayStatusPause);

            long now = TimeProvider.currentTimeMillis();
            state.setPositionMs(state.getPositionMs() + (int) (now - state.getPositionMeasuredAt()));
            state.setPositionMeasuredAt(now);
            state.updated();
        }
    }

    private void handleNext() {
        if (!state.hasTracks()) return;

        StateWrapper.NextPlayable next = state.nextPlayable(conf);
        if (next == StateWrapper.NextPlayable.AUTOPLAY) {
            loadAutoplay();
            return;
        }

        if (next.isOk()) {
            state.setPositionMs(0);
            state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
            loadTrack(next == StateWrapper.NextPlayable.OK_PLAY);
        } else {
            LOGGER.fatal("Failed loading next song: " + next);
        }
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
                loadTrack(true);

                LOGGER.debug(String.format("Loading context for autoplay, uri: %s", newContext));
            } else if (resp.statusCode == 204) {
                MercuryRequests.StationsWrapper station = session.mercury().sendSync(MercuryRequests.getStationFor(context));
                state.loadStation(station);

                state.setPositionMs(0);
                state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
                loadTrack(true);

                LOGGER.debug(String.format("Loading context for autoplay (using radio-apollo), uri: %s", state.getContextUri()));
            } else {
                LOGGER.fatal("Failed retrieving autoplay context, code: " + resp.statusCode);
                panicState();
            }
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading autoplay station!", ex);
            panicState();
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState();
        }
    }

    private void handlePrev() {
        if (!state.hasTracks()) return;

        if (getPosition() < 3000) {
            StateWrapper.PreviousPlayable prev = state.previousPlayable();
            if (prev.isOk()) {
                state.setPositionMs(0);
                state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
                loadTrack(true);
            } else {
                LOGGER.fatal("Failed loading previous song: " + prev);
            }
        } else {
            state.setPositionMs(0);
            state.setPositionMeasuredAt(TimeProvider.currentTimeMillis());
            if (trackHandler != null) trackHandler.sendSeek(0);
            state.updated();
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
        return state.hasTracks() ? state.getCurrentTrack() : null;
    }

    public interface Configuration {
        @NotNull
        AudioQuality preferredQuality();

        boolean preloadEnabled();

        float normalisationPregain();

        @Nullable
        String[] mixerSearchKeywords();

        boolean logAvailableMixers();

        int initialVolume();

        boolean autoplayEnabled();

        boolean useCdnForTracks();

        boolean useCdnForEpisodes();

        boolean enableLoadingState();
    }
}
