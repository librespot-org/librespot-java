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
import xyz.gianlu.librespot.connectstate.DeviceStateHandler.PlayCommandHelper;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.PlayerRunner.PushToMixerReason;
import xyz.gianlu.librespot.player.PlayerRunner.TrackHandler;
import xyz.gianlu.librespot.player.codecs.AudioQuality;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static spotify.player.proto.ContextTrackOuterClass.ContextTrack;

/**
 * @author Gianlu
 */
public class Player implements Closeable, DeviceStateHandler.Listener, PlayerRunner.Listener {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final Session session;
    private final Configuration conf;
    private final PlayerRunner runner;
    private StateWrapper state;
    private TrackHandler trackHandler;
    private TrackHandler crossfadeHandler;
    private TrackHandler preloadTrackHandler;

    public Player(@NotNull Player.Configuration conf, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        new Thread(runner = new PlayerRunner(session, conf, this), "player-runner-" + runner.hashCode()).start();
    }

    public void initState() {
        this.state = new StateWrapper(session);
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

    public void load(@NotNull String uri, boolean play) {
        try {
            state.loadContext(uri);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState();
            return;
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState();
            return;
        }

        loadTrack(play, PushToMixerReason.None);
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

        loadTrack(!cmd.getPlayback().getIsPaused(), PushToMixerReason.None);
    }

    private void handleLoad(@NotNull JsonObject obj) {
        LOGGER.debug(String.format("Loading context (play), uri: %s", PlayCommandHelper.getContextUri(obj)));

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

        Boolean play = PlayCommandHelper.isInitiallyPaused(obj);
        if (play == null) play = true;
        loadTrack(play, PushToMixerReason.None);
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
                state.updateContext(PlayCommandHelper.getContext(data.obj()));
                state.updated();
                break;
            default:
                LOGGER.warn("Endpoint left unhandled: " + endpoint);
                break;
        }
    }

    @Override
    public void volumeChanged() {
        runner.setVolume(state.getVolume());
    }

    @Override
    public void notActive() {
        runner.stopMixer();
    }

    @Override
    public void requestStateUpdate() {
        // Not interested
    }

    private void handlePlayPause() {
        if (state.isPlaying()) handlePause();
        else handleResume();
    }

    @Override
    public void startedLoading(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            state.setState(true, null, true);
            state.updated();
        }
    }

    private void updateStateWithHandler() {
        Metadata.Episode episode;
        Metadata.Track track;
        if ((track = trackHandler.track()) != null) state.enrichWithMetadata(track);
        else if ((episode = trackHandler.episode()) != null) state.enrichWithMetadata(episode);
        else LOGGER.warn("Couldn't update metadata!");
    }

    @Override
    public void finishedLoading(@NotNull TrackHandler handler, int pos) {
        if (handler == trackHandler) {
            state.setState(true, null, false);

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
    public void endOfTrack(@NotNull TrackHandler handler, @Nullable String uri, boolean fadeOut) {
        if (handler == trackHandler) {
            if (state.isRepeatingTrack()) {
                state.setPosition(0);

                LOGGER.trace(String.format("End of track. Repeating. {fadeOut: %b}", fadeOut));
                loadTrack(true, PushToMixerReason.None);
            } else {
                LOGGER.trace(String.format("End of track. Proceeding with next. {fadeOut: %b}", fadeOut));
                handleNext(null);

                PlayableId curr;
                if (uri != null && (curr = state.getCurrentPlayable()) != null && !curr.toSpotifyUri().equals(uri))
                    LOGGER.warn(String.format("Fade out track URI is different from next track URI! {next: %s, crossfade: %s}", curr, uri));
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
    public void crossfadeNextTrack(@NotNull TrackHandler handler, @Nullable String uri) {
        if (handler == trackHandler) {
            PlayableId next = state.nextPlayableDoNotSet();
            if (next == null) return;

            if (uri != null && !next.toSpotifyUri().equals(uri))
                LOGGER.warn(String.format("Fade out track URI is different from next track URI! {next: %s, crossfade: %s}", next, uri));

            if (preloadTrackHandler != null && preloadTrackHandler.isTrack(next)) {
                crossfadeHandler = preloadTrackHandler;
            } else {
                LOGGER.warn("Did not preload crossfade track. That's bad.");
                crossfadeHandler = runner.load(next, 0);
            }

            crossfadeHandler.waitReady();
            LOGGER.info("Crossfading to next track.");
            crossfadeHandler.pushToMixer(PushToMixerReason.Fade);
        }
    }

    @Override
    public @NotNull Map<String, String> metadataFor(@NotNull PlayableId id) {
        return state.metadataFor(id);
    }

    @Override
    public void finishedSeek(@NotNull TrackHandler handler) {
        if (handler == trackHandler) state.updated();
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

            state.setState(true, false, true);
            state.updated();
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
    }

    private void panicState() {
        runner.stopMixer();
        state.setState(false, false, false);
        state.updated();
    }

    private void loadTrack(boolean play, @NotNull PushToMixerReason reason) {
        if (trackHandler != null) {
            trackHandler.stop();
            trackHandler = null;
        }

        PlayableId id = state.getCurrentPlayableOrThrow();
        if (crossfadeHandler != null && crossfadeHandler.isTrack(id)) {
            trackHandler = crossfadeHandler;
            if (preloadTrackHandler == crossfadeHandler) preloadTrackHandler = null;
            crossfadeHandler = null;

            if (trackHandler.isReady()) {
                updateStateWithHandler();

                try {
                    state.setPosition(trackHandler.time());
                } catch (Codec.CannotGetTimeException ignored) {
                }

                state.setState(true, !play, false);
                state.updated();
            } else {
                state.setState(true, !play, true);
                state.updated();
            }

            if (!play) runner.pauseMixer();
        } else {
            if (preloadTrackHandler != null && preloadTrackHandler.isTrack(id)) {
                trackHandler = preloadTrackHandler;
                preloadTrackHandler = null;

                if (trackHandler.isReady()) {
                    updateStateWithHandler();

                    trackHandler.seek(state.getPosition());
                    state.setState(true, !play, false);
                    state.updated();
                } else {
                    state.setState(true, !play, true);
                    state.updated();
                }
            } else {
                trackHandler = runner.load(id, state.getPosition());
                state.setState(true, !play, true);
                state.updated();
            }

            if (play) {
                trackHandler.pushToMixer(reason);
                runner.playMixer();
            }
        }
    }

    private void handleResume() {
        if (state.isPaused()) {
            if (!trackHandler.isInMixer()) trackHandler.pushToMixer(PushToMixerReason.None);
            runner.playMixer();
            state.setState(true, false, false);

            try {
                state.setPosition(trackHandler.time());
            } catch (Codec.CannotGetTimeException ignored) {
            }

            state.updated();
        }
    }

    private void handlePause() {
        if (state.isPlaying()) {
            runner.pauseMixer();
            state.setState(true, true, false);

            try {
                state.setPosition(trackHandler.time());
            } catch (Codec.CannotGetTimeException ignored) {
            }

            state.updated();
        }
    }

    private void setQueue(@NotNull JsonObject obj) {
        List<ContextTrack> prevTracks = PlayCommandHelper.getPrevTracks(obj);
        List<ContextTrack> nextTracks = PlayCommandHelper.getNextTracks(obj);
        if (prevTracks == null && nextTracks == null) throw new IllegalArgumentException();

        state.setQueue(prevTracks, nextTracks);
        state.updated();
    }

    private void addToQueue(@NotNull JsonObject obj) {
        ContextTrack track = PlayCommandHelper.getTrack(obj);
        if (track == null) throw new IllegalArgumentException();

        state.addToQueue(track);
        state.updated();
    }

    private void handleNext(@Nullable JsonObject obj) {
        ContextTrack track = null;
        if (obj != null) track = PlayCommandHelper.getTrack(obj);

        if (track != null) {
            state.skipTo(track);
            loadTrack(true, PushToMixerReason.Next);
            return;
        }

        StateWrapper.NextPlayable next = state.nextPlayable(conf);
        if (next == StateWrapper.NextPlayable.AUTOPLAY) {
            loadAutoplay();
            return;
        }

        if (next.isOk()) {
            state.setPosition(0);
            loadTrack(next == StateWrapper.NextPlayable.OK_PLAY, PushToMixerReason.Next);
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

                loadTrack(true, PushToMixerReason.None);

                LOGGER.debug(String.format("Loading context for autoplay, uri: %s", newContext));
            } else if (resp.statusCode == 204) {
                MercuryRequests.StationsWrapper station = session.mercury().sendSync(MercuryRequests.getStationFor(context));
                state.loadContextWithTracks(station.uri(), station.tracks());

                loadTrack(true, PushToMixerReason.None);

                LOGGER.debug(String.format("Loading context for autoplay (using radio-apollo), uri: %s", state.getContextUri()));
            } else {
                LOGGER.fatal("Failed retrieving autoplay context, code: " + resp.statusCode);

                state.setPosition(0);
                state.setState(true, false, false);
                state.updated();
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
                loadTrack(true, PushToMixerReason.Prev);
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
    public void close() throws IOException {
        if (trackHandler != null) {
            trackHandler.close();
            trackHandler = null;
        }

        if (crossfadeHandler != null) {
            crossfadeHandler.close();
            crossfadeHandler = null;
        }

        if (preloadTrackHandler != null) {
            preloadTrackHandler.close();
            preloadTrackHandler = null;
        }

        runner.close();
        if (state != null) state.removeListener(this);
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

    /**
     * @return The current position of the player or {@code -1} if unavailable (most likely if it's playing an episode).
     */
    public long time() {
        try {
            return trackHandler == null ? 0 : trackHandler.time();
        } catch (Codec.CannotGetTimeException ex) {
            return -1;
        }
    }

    public interface Configuration {
        @NotNull
        AudioQuality preferredQuality();

        @NotNull
        AudioOutput output();

        @Nullable
        File outputPipe();

        boolean preloadEnabled();

        boolean enableNormalisation();

        float normalisationPregain();

        @Nullable
        String[] mixerSearchKeywords();

        boolean logAvailableMixers();

        int initialVolume();

        boolean autoplayEnabled();

        int crossfadeDuration();
    }
}
