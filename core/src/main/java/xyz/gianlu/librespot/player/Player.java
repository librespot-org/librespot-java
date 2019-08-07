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
import xyz.gianlu.librespot.player.PlayerRunner.TrackHandler;
import xyz.gianlu.librespot.player.codecs.AudioQuality;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static spotify.player.proto.ContextTrackOuterClass.ContextTrack;

/**
 * @author Gianlu
 */
public class Player implements Closeable, DeviceStateHandler.Listener, PlayerRunner.Listener {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final Session session;
    private final StateWrapper state;
    private final Configuration conf;
    private final PlayerRunner runner;
    private TrackHandler trackHandler;
    private TrackHandler crossfadeHandler;
    private TrackHandler preloadTrackHandler;

    public Player(@NotNull Player.Configuration conf, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.state = new StateWrapper(session);

        new Thread(runner = new PlayerRunner(session, conf, this), "player-runner-" + runner.hashCode()).start();

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
                setQueue(data.obj());
                break;
            case UpdateContext:
                state.updateContext(data.obj());
                state.updated();
                break;
            default:
                LOGGER.warn("Endpoint left unhandled: " + endpoint);
                break;
        }
    }

    @Override
    public void volumeChanged() {
        PlayerRunner.Controller controller = runner.controller();
        if (controller != null) controller.setVolume(state.getVolume());
    }

    @Override
    public void notActive() {
        runner.stopMixer();
    }

    private void handlePlayPause() {
        if (state.isPlaying()) handlePause();
        else handleResume();
    }

    @Override
    public void startedLoading(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            state.setState(true, false, conf.enableLoadingState());
            state.updated();
        }
    }

    private void updateStateWithHandler() { // FIXME: Check that we are updating the correct track
        Metadata.Episode episode;
        Metadata.Track track;
        if ((track = trackHandler.track()) != null) state.enrichWithMetadata(track);
        else if ((episode = trackHandler.episode()) != null) state.enrichWithMetadata(episode);
        else LOGGER.warn("Couldn't update track duration!");
    }

    @Override
    public void finishedLoading(@NotNull TrackHandler handler, int pos) {
        if (handler == trackHandler) {
            state.setBuffering(false);

            updateStateWithHandler();

            state.setPosition(pos);
            state.updated();
        } else if (handler == preloadTrackHandler) {
            LOGGER.trace("Preloaded track is ready.");
        }
    }

    @Override
    public void mixerError(@NotNull Exception ex) {
        LOGGER.fatal("Mixer error!", ex);
        panicState();
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
            if (next != null) {
                preloadTrackHandler = runner.load(next, 0);
                LOGGER.trace("Started next track preload, gid: " + Utils.bytesToHex(next.getGid()));
            }
        }
    }

    @Override
    public void crossfadeNextTrack(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            PlayableId next = state.nextPlayableDoNotSet();
            if (next == null) return;

            if (preloadTrackHandler != null && preloadTrackHandler.isTrack(next)) {
                crossfadeHandler = preloadTrackHandler;
            } else {
                LOGGER.warn("Did not preload crossfade track. That's bad.");
                crossfadeHandler = runner.load(next, 0);
            }

            LOGGER.info("Crossfading to next track.");
            crossfadeHandler.pushToMixer();
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
        if (trackHandler != null) trackHandler.seek(pos);
        state.updated();
    }

    private void panicState() {
        runner.stopMixer();
        state.setState(false, false, false);
        state.updated();
    }

    private void loadTrack(boolean play) {
        if (trackHandler != null) trackHandler.stop();

        boolean buffering = preloadTrackHandler == null && conf.enableLoadingState();
        PlayableId id = state.getCurrentPlayableOrThrow();
        if (crossfadeHandler != null && crossfadeHandler.isTrack(id)) {
            trackHandler = crossfadeHandler;
            if (preloadTrackHandler == crossfadeHandler) preloadTrackHandler = null;
            crossfadeHandler = null;

            updateStateWithHandler();

            try {
                state.setPosition(trackHandler.time());
            } catch (Codec.CannotGetTimeException ignored) {
            }

            if (!play) runner.pauseMixer();
        } else {
            if (preloadTrackHandler != null && preloadTrackHandler.isTrack(id)) {
                trackHandler = preloadTrackHandler;
                preloadTrackHandler = null;

                updateStateWithHandler();

                trackHandler.seek(state.getPosition());
            } else {
                trackHandler = runner.load(id, state.getPosition());
            }

            if (play) {
                trackHandler.pushToMixer();
                runner.playMixer();
            }
        }

        if (play) state.setState(true, false, buffering);
        else state.setState(true, true, buffering);
        state.updated();
    }

    private void handleResume() {
        if (state.isPaused()) {
            runner.playMixer();
            state.setState(true, false, false);
            state.updated();
        }
    }

    private void handlePause() {
        if (state.isPlaying()) {
            runner.pauseMixer();
            state.setState(true, true, false);
            state.updated();
        }
    }

    private void setQueue(@NotNull JsonObject obj) {
        List<ContextTrack> prevTracks = PlayCommandWrapper.getPrevTracks(obj);
        List<ContextTrack> nextTracks = PlayCommandWrapper.getNextTracks(obj);
        if (prevTracks == null && nextTracks == null) throw new IllegalArgumentException();

        state.setQueue(prevTracks, nextTracks);
        state.updated();
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
            if (trackHandler != null) trackHandler.seek(0);
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
        return trackHandler == null ? null : trackHandler.track();
    }

    @Nullable
    public Metadata.Episode currentEpisode() {
        return trackHandler == null ? null : trackHandler.episode();
    }

    @Nullable
    public PlayableId currentPlayableId() {
        return state.getCurrentPlayable();
    }

    public interface Configuration {
        @NotNull
        AudioQuality preferredQuality();

        @NotNull
        AudioOutput output();

        @Nullable
        File outputPipe();

        boolean preloadEnabled();

        float normalisationPregain();

        @Nullable
        String[] mixerSearchKeywords();

        boolean logAvailableMixers();

        int initialVolume();

        boolean autoplayEnabled();

        boolean enableLoadingState();

        int crossfadeDuration();
    }
}
