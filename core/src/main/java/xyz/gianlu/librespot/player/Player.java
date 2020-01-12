package xyz.gianlu.librespot.player;

import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.metadata.proto.Metadata;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.player.proto.transfer.TransferStateOuterClass;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler.PlayCommandHelper;
import xyz.gianlu.librespot.core.EventServiceHelper;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.PlayerRunner.PushToMixerReason;
import xyz.gianlu.librespot.player.PlayerRunner.TrackHandler;
import xyz.gianlu.librespot.player.StateWrapper.NextPlayable;
import xyz.gianlu.librespot.player.codecs.AudioQuality;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static spotify.player.proto.ContextTrackOuterClass.ContextTrack;

/**
 * @author Gianlu
 */
public class Player implements Closeable, DeviceStateHandler.Listener, PlayerRunner.Listener {
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NameThreadFactory((r) -> "release-line-scheduler-" + r.hashCode()));
    private final Session session;
    private final Configuration conf;
    private final PlayerRunner runner;
    private final EventsDispatcher events = new EventsDispatcher();
    private StateWrapper state;
    private TrackHandler trackHandler;
    private TrackHandler crossfadeHandler;
    private TrackHandler preloadTrackHandler;
    private ScheduledFuture<?> releaseLineFuture = null;

    public Player(@NotNull Player.Configuration conf, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        new Thread(runner = new PlayerRunner(session, conf, this), "player-runner-" + runner.hashCode()).start();
    }

    public void addEventsListener(@NotNull EventsListener listener) {
        events.listeners.add(listener);
    }

    public void removeEventsListener(@NotNull EventsListener listener) {
        events.listeners.remove(listener);
    }

    public void initState() {
        this.state = new StateWrapper(session);
        state.addListener(this);
    }

    public void volumeUp() {
        if (state == null) return;
        setVolume(Math.min(PlayerRunner.VOLUME_MAX, state.getVolume() + PlayerRunner.VOLUME_ONE_STEP));
    }

    public void volumeDown() {
        if (state == null) return;
        setVolume(Math.max(0, state.getVolume() - PlayerRunner.VOLUME_ONE_STEP));
    }

    public void setVolume(int val) {
        if (val < 0 || val > PlayerRunner.VOLUME_MAX)
            throw new IllegalArgumentException(String.valueOf(val));

        if (state == null) return;
        state.setVolume(val);
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

        events.dispatchContextChanged();
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

        events.dispatchContextChanged();
        loadTrack(!cmd.getPlayback().getIsPaused(), PushToMixerReason.None);

        try {
            EventServiceHelper.announceNewSessionId(session, state);
            EventServiceHelper.announceNewPlaybackId(session, state);
        } catch (IOException e) {
            e.printStackTrace(); // FIXME
        }
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

        events.dispatchContextChanged();

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
        events.inactiveSession(false);
        if (runner.stopAndRelease()) LOGGER.debug("Released line due to inactivity.");
    }

    @Override
    public void startedLoading(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            state.setBuffering(true);
            state.updated();
        }
    }

    private void updateStateWithHandler() {
        Metadata.Episode episode;
        Metadata.Track track;
        if ((track = trackHandler.track()) != null) state.enrichWithMetadata(track);
        else if ((episode = trackHandler.episode()) != null) state.enrichWithMetadata(episode);
        else LOGGER.warn("Couldn't update metadata!");

        events.metadataAvailable();
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
    public void endOfTrack(@NotNull TrackHandler handler, @Nullable String uri, boolean fadeOut) {
        if (handler == trackHandler) {
            try {
                EventServiceHelper.reportPlayback(session, state, state.getCurrentPlayableOrThrow().toSpotifyUri(),
                        new EventServiceHelper.PlaybackIntervals());
            } catch (IOException e) {
                e.printStackTrace(); // FIXME
            }

            LOGGER.trace(String.format("End of track. Proceeding with next. {fadeOut: %b}", fadeOut));
            handleNext(null);

            PlayableId curr;
            if (uri != null && (curr = state.getCurrentPlayable()) != null && !curr.toSpotifyUri().equals(uri))
                LOGGER.warn(String.format("Fade out track URI is different from next track URI! {next: %s, crossfade: %s}", curr, uri));
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

            if (preloadTrackHandler != null && preloadTrackHandler.isPlayable(next)) {
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
    public void abortedCrossfade(@NotNull TrackHandler handler) {
        if (handler == trackHandler) {
            if (crossfadeHandler == preloadTrackHandler) preloadTrackHandler = null;
            crossfadeHandler = null;

            LOGGER.trace("Aborted crossfade.");
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
            if (ex instanceof AbsChunkedInputStream.ChunkException)
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

            state.setBuffering(true);
            state.updated();

            events.playbackHaltStateChanged(true);
        }
    }

    @Override
    public void playbackResumedFromHalt(@NotNull TrackHandler handler, int chunk, long diff) {
        if (handler == trackHandler) {
            LOGGER.debug(String.format("Playback resumed, chunk %d retrieved, took %dms.", chunk, diff));

            state.setPosition(state.getPosition() - diff);
            state.setBuffering(false);
            state.updated();

            events.playbackHaltStateChanged(false);
        }
    }

    private void handleSeek(int pos) {
        state.setPosition(pos);
        if (trackHandler != null) trackHandler.seek(pos);
        events.dispatchSeek(pos);
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

        state.renewPlaybackId();

        PlayableId id = state.getCurrentPlayableOrThrow();
        if (crossfadeHandler != null && crossfadeHandler.isPlayable(id)) {
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

            events.dispatchTrackChanged();

            if (!play) {
                runner.pauseMixer();
                events.dispatchPlaybackPaused();
            } else {
                events.dispatchPlaybackResumed();
            }
        } else {
            if (preloadTrackHandler != null && preloadTrackHandler.isPlayable(id)) {
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

            events.dispatchTrackChanged();

            if (play) {
                trackHandler.pushToMixer(reason);
                runner.playMixer();
                events.dispatchPlaybackResumed();
            } else {
                events.dispatchPlaybackPaused();
            }
        }

        if (releaseLineFuture != null) {
            releaseLineFuture.cancel(true);
            releaseLineFuture = null;
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
            events.dispatchPlaybackResumed();

            if (releaseLineFuture != null) {
                releaseLineFuture.cancel(true);
                releaseLineFuture = null;
            }
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
            events.dispatchPlaybackPaused();

            if (releaseLineFuture != null) releaseLineFuture.cancel(true);
            releaseLineFuture = scheduler.schedule(() -> {
                if (!state.isPaused()) return;

                events.inactiveSession(true);
                if (runner.pauseAndRelease()) LOGGER.debug("Released line after a period of inactivity.");
            }, conf.releaseLineDelay(), TimeUnit.SECONDS);
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

        NextPlayable next = state.nextPlayable(conf);
        if (next == NextPlayable.AUTOPLAY) {
            loadAutoplay();
            return;
        }

        if (next.isOk()) {
            state.setPosition(0);
            loadTrack(next == NextPlayable.OK_PLAY || next == NextPlayable.OK_REPEAT, PushToMixerReason.Next);
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

        String contextDesc = state.getContextMetadata("context_description");

        try {
            MercuryClient.Response resp = session.mercury().sendSync(MercuryRequests.autoplayQuery(context));
            if (resp.statusCode == 200) {
                String newContext = resp.payload.readIntoString(0);
                state.loadContext(newContext);
                state.setContextMetadata("context_description", contextDesc);

                events.dispatchContextChanged();
                loadTrack(true, PushToMixerReason.None);

                LOGGER.debug(String.format("Loading context for autoplay, uri: %s", newContext));
            } else if (resp.statusCode == 204) {
                MercuryRequests.StationsWrapper station = session.mercury().sendSync(MercuryRequests.getStationFor(context));
                state.loadContextWithTracks(station.uri(), station.tracks());
                state.setContextMetadata("context_description", contextDesc);

                events.dispatchContextChanged();
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

        events.listeners.clear();

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

        int releaseLineDelay();

        boolean stopPlaybackOnChunkError();
    }

    public interface EventsListener {
        void onContextChanged(@NotNull String newUri);

        void onTrackChanged(@NotNull PlayableId id, @Nullable Metadata.Track track, @Nullable Metadata.Episode episode);

        void onPlaybackPaused(long trackTime);

        void onPlaybackResumed(long trackTime);

        void onTrackSeeked(long trackTime);

        void onMetadataAvailable(@Nullable Metadata.Track track, @Nullable Metadata.Episode episode);

        void onPlaybackHaltStateChanged(boolean halted, long trackTime);

        void onInactiveSession(boolean timeout);
    }

    private class EventsDispatcher {
        private final ExecutorService executorService = Executors.newSingleThreadExecutor(new NameThreadFactory((r) -> "player-events-" + r.hashCode()));
        private final List<EventsListener> listeners = new ArrayList<>();

        void dispatchPlaybackPaused() {
            long trackTime = state.getPosition();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackPaused(trackTime));
        }

        void dispatchPlaybackResumed() {
            long trackTime = state.getPosition();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackResumed(trackTime));
        }

        void dispatchContextChanged() {
            String uri = state.getContextUri();
            if (uri == null) return;

            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onContextChanged(uri));
        }

        void dispatchTrackChanged() {
            PlayableId id = state.getCurrentPlayable();
            if (id == null) return;

            Metadata.Track track;
            Metadata.Episode episode;
            if (trackHandler != null && trackHandler.isPlayable(id)) {
                track = trackHandler.track();
                episode = trackHandler.episode();
            } else {
                track = null;
                episode = null;
            }

            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onTrackChanged(id, track, episode));
        }

        public void dispatchSeek(int pos) {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onTrackSeeked(pos));
        }

        public void metadataAvailable() {
            if (trackHandler == null) return;

            Metadata.Track track = trackHandler.track();
            Metadata.Episode episode = trackHandler.episode();
            if (track == null && episode == null) return;

            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onMetadataAvailable(track, episode));
        }

        public void playbackHaltStateChanged(boolean halted) {
            long trackTime = state.getPosition();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackHaltStateChanged(halted, trackTime));
        }

        public void inactiveSession(boolean timeout) {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onInactiveSession(timeout));
        }
    }
}
