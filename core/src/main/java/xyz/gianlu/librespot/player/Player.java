package xyz.gianlu.librespot.player;

import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.metadata.proto.Metadata;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.player.proto.transfer.TransferStateOuterClass;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler.PlayCommandWrapper;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.mercury.model.UnsupportedId;
import xyz.gianlu.librespot.player.codecs.AudioQuality;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;

import java.io.Closeable;
import java.io.IOException;

import static spotify.player.proto.ContextTrackOuterClass.ContextTrack;

/**
 * @author Gianlu
 */
public class Player implements TrackHandler.Listener, Closeable, DeviceStateHandler.Listener {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final Session session;
    private final StateWrapper state;
    private final Configuration conf;
    private final LinesHolder lines;
    private TrackHandler trackHandler;
    private TrackHandler preloadTrackHandler;

    public Player(@NotNull Player.Configuration conf, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.state = new StateWrapper(session);
        this.lines = new LinesHolder();

        state.addListener(this);
    }

    public void playPause() {
        handlePlayPause();
    }

    public void play() {
        handleResume();
    }

    public void pause() {
        handlePause();
    }

    public void next() {
        handleNext(null);
    }

    public void previous() {
        handlePrev();
    }

    private void transferState(TransferStateOuterClass.@NotNull TransferState cmd) {
        LOGGER.debug(String.format("Loading context (transfer), uri: %s", cmd.getCurrentSession().getContext().getUri()));

        try {
            state.transfer(cmd);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState();
            return;
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState();
            return;
        }

        loadTrack(!cmd.getPlayback().getIsPaused());
    }

    private void handleLoad(@NotNull JsonObject obj) {
        LOGGER.debug(String.format("Loading context (play), uri: %s", PlayCommandWrapper.getContextUri(obj)));

        try {
            state.load(obj);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState();
            return;
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState();
            return;
        }

        Boolean play = PlayCommandWrapper.isInitiallyPaused(obj);
        if (play == null) play = true;
        loadTrack(play);
    }

    @Override
    public void ready() {
    }

    @Override
    public void command(@NotNull DeviceStateHandler.Endpoint endpoint, @NotNull DeviceStateHandler.CommandBody data) throws InvalidProtocolBufferException {
        LOGGER.debug("Received command: " + endpoint);

        switch (endpoint) {
            case Play:
                handleLoad(data.obj());
                break;
            case Transfer:
                transferState(TransferStateOuterClass.TransferState.parseFrom(data.data()));
                break;
            case Resume:
                handleResume();
                break;
            case Pause:
                handlePause();
                break;
            case SeekTo:
                handleSeek(data.valueInt());
                break;
            case SkipNext:
                handleNext(data.obj());
                break;
            case SkipPrev:
                handlePrev();
                break;
            case SetRepeatingContext:
                state.setRepeatingContext(data.valueBool());
                state.updated();
                break;
            case SetRepeatingTrack:
                state.setRepeatingTrack(data.valueBool());
                state.updated();
                break;
            case SetShufflingContext:
                state.setShufflingContext(data.valueBool());
                state.updated();
                break;
            case AddToQueue:
                addToQueue(data.obj());
                break;
            case SetQueue:
            case UpdateContext:
                System.out.println("UNSUPPORTED: " + data.obj()); // TODO
                break;
            default:
                LOGGER.warn("Endpoint left unhandled: " + endpoint);
                break;
        }
    }

    @Override
    public void volumeChanged() {
        if (trackHandler != null) {
            PlayerRunner.Controller controller = trackHandler.controller();
            if (controller != null) controller.setVolume(state.getVolume());
        }
    }

    private void handlePlayPause() {
        if (state.isActuallyPlaying()) handlePause();
        else handleResume();
    }

    @Override
    public void startedLoading(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            state.setState(true, false, conf.enableLoadingState());
            state.updated();
        }
    }

    private void updateStateWithHandler() {
        Metadata.Episode episode;
        Metadata.Track track;
        if ((track = trackHandler.track()) != null) state.enrichWithMetadata(track);
        else if ((episode = trackHandler.episode()) != null) state.enrichWithMetadata(episode);
        else LOGGER.warn("Couldn't update track duration!");
    }

    @Override
    public void finishedLoading(@NotNull TrackHandler handler, int pos, boolean play) {
        if (handler == trackHandler) {
            if (play) state.setState(true, false, false);
            else state.setState(false, true, false);

            updateStateWithHandler();

            state.setPosition(pos);
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
                handleNext(null);
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
                state.setPosition(0);

                LOGGER.trace("End of track. Repeating.");
                loadTrack(true);
            } else {
                LOGGER.trace("End of track. Proceeding with next.");
                handleNext(null);
            }
        }
    }

    @Override
    public void preloadNextTrack(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            PlayableId next = state.nextPlayableDoNotSet();
            if (next != null && !(next instanceof UnsupportedId)) {
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
                state.setState(true, false, true);
                state.updated();
            }
        }
    }

    @Override
    public void playbackResumedFromHalt(@NotNull TrackHandler handler, int chunk, long diff) {
        if (handler == trackHandler) {
            LOGGER.debug(String.format("Playback resumed, chunk %d retrieved, took %dms.", chunk, diff));

            state.setPosition(state.getPosition() - diff);
            state.setState(true, false, false);
            state.updated();
        }
    }

    private void handleSeek(int pos) {
        state.setPosition(pos);
        if (trackHandler != null) trackHandler.sendSeek(pos);
        state.updated();
    }

    @Override
    public int getVolume() {
        return state.getVolume();
    }

    private void panicState() {
        if (trackHandler != null) trackHandler.sendStop();
        state.setState(false, false, false);
        state.updated();
    }

    private void loadTrack(boolean play) {
        if (trackHandler != null) trackHandler.close();

        boolean buffering = preloadTrackHandler == null && conf.enableLoadingState();
        PlayableId id = state.getCurrentPlayable();
        if (preloadTrackHandler != null && preloadTrackHandler.isTrack(id)) {
            trackHandler = preloadTrackHandler;
            preloadTrackHandler = null;

            updateStateWithHandler();

            trackHandler.sendSeek(state.getPosition());
            if (play) trackHandler.sendPlay();
        } else {
            trackHandler = new TrackHandler(session, lines, conf, this);
            trackHandler.sendLoad(id, play, state.getPosition());
        }

        if (play) state.setState(true, false, buffering);
        else state.setState(false, true, buffering);
        state.updated();
    }

    private void handleResume() {
        if (state.isPaused()) {
            state.setState(true, false, false);
            if (trackHandler != null) trackHandler.sendPlay();
            state.updated();
        }
    }

    private void handlePause() {
        if (state.isActuallyPlaying()) {
            if (trackHandler != null) trackHandler.sendPause();
            state.setState(false, true, false);
            state.updated();
        }
    }

    private void addToQueue(@NotNull JsonObject obj) {
        ContextTrack track = PlayCommandWrapper.getTrack(obj);
        if (track == null) throw new IllegalArgumentException();

        state.addToQueue(track);
        state.updated();
    }

    private void handleNext(@Nullable JsonObject obj) {
        ContextTrack track = null;
        if (obj != null) track = PlayCommandWrapper.getTrack(obj);

        if (track != null) {
            state.skipTo(track);
            loadTrack(true);
            return;
        }

        StateWrapper.NextPlayable next = state.nextPlayable(conf);
        if (next == StateWrapper.NextPlayable.AUTOPLAY) {
            loadAutoplay();
            return;
        }

        if (next.isOk()) {
            state.setPosition(0);
            loadTrack(next == StateWrapper.NextPlayable.OK_PLAY);
        } else {
            LOGGER.fatal("Failed loading next song: " + next);
            panicState();
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
                state.loadContext(newContext);

                loadTrack(true);

                LOGGER.debug(String.format("Loading context for autoplay, uri: %s", newContext));
            } else if (resp.statusCode == 204) {
                MercuryRequests.StationsWrapper station = session.mercury().sendSync(MercuryRequests.getStationFor(context));
                state.loadContextWithTracks(station.uri(), station.tracks());

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
        if (state.getPosition() < 3000) {
            StateWrapper.PreviousPlayable prev = state.previousPlayable();
            if (prev.isOk()) {
                state.setPosition(0);
                loadTrack(true);
            } else {
                LOGGER.fatal("Failed loading previous song: " + prev);
                panicState();
            }
        } else {
            state.setPosition(0);
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

        state.removeListener(this);
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
        return state.getCurrentPlayable();
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
