package xyz.gianlu.librespot.player;

import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.context.ContextTrackOuterClass.ContextTrack;
import com.spotify.metadata.Metadata;
import com.spotify.transfer.TransferStateOuterClass;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import xyz.gianlu.librespot.audio.AbsChunkedInputStream;
import xyz.gianlu.librespot.audio.PlayableContentFeeder;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.metadata.ImageId;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.player.StateWrapper.NextPlayable;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;
import xyz.gianlu.librespot.player.metadata.MetadataPipe;
import xyz.gianlu.librespot.player.metrics.NewPlaybackIdEvent;
import xyz.gianlu.librespot.player.metrics.NewSessionIdEvent;
import xyz.gianlu.librespot.player.metrics.PlaybackMetrics;
import xyz.gianlu.librespot.player.metrics.PlayerMetrics;
import xyz.gianlu.librespot.player.mixing.AudioSink;
import xyz.gianlu.librespot.player.mixing.LineHelper;
import xyz.gianlu.librespot.player.playback.PlayerSession;
import xyz.gianlu.librespot.player.state.DeviceStateHandler;
import xyz.gianlu.librespot.player.state.DeviceStateHandler.PlayCommandHelper;

import javax.sound.sampled.LineUnavailableException;
import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author Gianlu
 */
public class Player implements Closeable, PlayerSession.Listener, AudioSink.Listener {
    public static final int VOLUME_MAX = 65536;
    private static final Logger LOGGER = LogManager.getLogger(Player.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NameThreadFactory((r) -> "release-line-scheduler-" + r.hashCode()));
    private final Session session;
    private final PlayerConfiguration conf;
    private final EventsDispatcher events;
    private final AudioSink sink;
    private final Map<String, PlaybackMetrics> metrics = new HashMap<>(5);
    private StateWrapper state;
    private PlayerSession playerSession;
    private ScheduledFuture<?> releaseLineFuture = null;
    private DeviceStateHandler.Listener deviceStateListener;

    public Player(@NotNull PlayerConfiguration conf, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.events = new EventsDispatcher(conf);
        this.sink = new AudioSink(conf, this);

        initState();
    }

    public void addEventsListener(@NotNull EventsListener listener) {
        events.listeners.add(listener);
    }

    public void removeEventsListener(@NotNull EventsListener listener) {
        events.listeners.remove(listener);
    }

    private void initState() {
        this.state = new StateWrapper(session, this, conf);
        state.addListener(deviceStateListener = new DeviceStateHandler.Listener() {
            @Override
            public void ready() {
            }

            @Override
            public void command(DeviceStateHandler.@NotNull Endpoint endpoint, @NotNull DeviceStateHandler.CommandBody data) throws InvalidProtocolBufferException {
                LOGGER.debug("Received command: " + endpoint);

                switch (endpoint) {
                    case Play:
                        handlePlay(data.obj());
                        break;
                    case Transfer:
                        handleTransferState(TransferStateOuterClass.TransferState.parseFrom(data.data()));
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
                        handleSkipNext(data.obj(), TransitionInfo.skippedNext(state));
                        break;
                    case SkipPrev:
                        handleSkipPrev();
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
                        handleAddToQueue(data.obj());
                        break;
                    case SetQueue:
                        handleSetQueue(data.obj());
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
                int vol = state.getVolume();
                if (conf.bypassSinkVolume) sink.setVolume(vol);
                events.volumeChanged(vol);
            }

            @Override
            public void notActive() {
                events.inactiveSession(false);
                sink.pause(true);
            }
        });
    }

    // ================================ //
    // =========== Commands =========== //
    // ================================ //

    public void volumeUp() {
        this.volumeUp(1);
    }

    public void volumeUp(int steps) {
        if (state == null) return;
        setVolume(Math.min(Player.VOLUME_MAX, state.getVolume() + steps * oneVolumeStep()));
    }

    public void volumeDown() {
        this.volumeDown(1);
    }

    public void volumeDown(int steps) {
        if (state == null) return;
        setVolume(Math.max(0, state.getVolume() - steps * oneVolumeStep()));
    }

    private int oneVolumeStep() {
        return Player.VOLUME_MAX / conf.volumeSteps;
    }

    public void setVolume(int val) {
        if (val < 0 || val > VOLUME_MAX)
            throw new IllegalArgumentException(String.valueOf(val));

        if (state == null) return;
        state.setVolume(val);
    }

    public void play() {
        handleResume();
    }

    public void playPause() {
        if (state.isPaused()) handleResume();
        else handlePause();
    }

    public void pause() {
        handlePause();
    }

    public void next() {
        handleSkipNext(null, TransitionInfo.skippedNext(state));
    }

    public void previous() {
        handleSkipPrev();
    }

    public void seek(int pos) {
        handleSeek(pos);
    }

    public void load(@NotNull String uri, boolean play) {
        try {
            String sessionId = state.loadContext(uri);
            events.contextChanged();

            loadSession(sessionId, play, true);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState(null);
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState(null);
        }
    }

    public void addToQueue(@NotNull String uri) {
        state.addToQueue(ContextTrack.newBuilder().setUri(uri).build());
        state.updated();
    }

    public void removeFromQueue(@NotNull String uri) {
        state.removeFromQueue(uri);
        state.updated();
    }

    @NotNull
    public Future<Player> ready() {
        CompletableFuture<Player> future = new CompletableFuture<>();
        if (state.isReady()) {
            future.complete(this);
            return future;
        }

        state.addListener(new DeviceStateHandler.Listener() {
            @Override
            public void ready() {
                state.removeListener(this);
                future.complete(Player.this);
            }

            @Override
            public void command(@NotNull DeviceStateHandler.Endpoint endpoint, @NotNull DeviceStateHandler.CommandBody data) {
            }

            @Override
            public void volumeChanged() {
            }

            @Override
            public void notActive() {
            }
        });
        return future;
    }

    public void waitReady() throws InterruptedException {
        try {
            ready().get();
        } catch (ExecutionException ignored) {
        }
    }


    // ================================ //
    // ======== Internal state ======== //
    // ================================ //

    /**
     * Enter a "panic" state where everything is stopped.
     *
     * @param reason Why we entered this state
     */
    private void panicState(@Nullable PlaybackMetrics.Reason reason) {
        sink.pause(true);
        state.setState(false, false, false);
        state.updated();

        if (reason == null) {
            metrics.clear();
        } else if (playerSession != null) {
            endMetrics(playerSession.currentPlaybackId(), reason, playerSession.currentMetrics(), state.getPosition());
        }

        events.panicState();
    }

    /**
     * Loads a new session by creating a new {@link PlayerSession}. Will also trigger {@link Player#loadTrack(boolean, TransitionInfo)}.
     *
     * @param sessionId The new session ID
     * @param play      Whether the playback should start immediately
     */
    private void loadSession(@NotNull String sessionId, boolean play, boolean withSkip) {
        LOGGER.debug("Loading session, id: {}, play: {}", sessionId, play);

        TransitionInfo trans = TransitionInfo.contextChange(state, withSkip);

        if (playerSession != null) {
            endMetrics(playerSession.currentPlaybackId(), trans.endedReason, playerSession.currentMetrics(), trans.endedWhen);

            playerSession.close();
            playerSession = null;
        }

        playerSession = new PlayerSession(session, sink, conf, sessionId, this);
        session.eventService().sendEvent(new NewSessionIdEvent(sessionId, state));

        loadTrack(play, trans);
    }

    /**
     * Loads a new track and pauses/resumes the sink accordingly.
     *
     * <b>This is called only to change track due to an external command (user interaction).</b>
     *
     * @param play  Whether the playback should start immediately
     * @param trans A {@link TransitionInfo} object containing information about this track change
     */
    private void loadTrack(boolean play, @NotNull TransitionInfo trans) {
        endMetrics(playerSession.currentPlaybackId(), trans.endedReason, playerSession.currentMetrics(), trans.endedWhen);

        LOGGER.debug("Loading track, id: {}, session: {}, playback: {}, play: {}", state.getCurrentPlayable(), playerSession.sessionId(), playerSession.currentPlaybackId(), play);
        String playbackId = playerSession.play(state.getCurrentPlayableOrThrow(), state.getPosition(), trans.startedReason);
        state.setPlaybackId(playbackId);
        session.eventService().sendEvent(new NewPlaybackIdEvent(state.getSessionId(), playbackId));

        if (play) sink.resume();
        else sink.pause(false);

        state.setState(true, !play, true);
        state.updated();

        events.trackChanged();
        if (play) events.playbackResumed();
        else events.playbackPaused();

        startMetrics(playbackId, trans.startedReason, state.getPosition());

        if (releaseLineFuture != null) {
            releaseLineFuture.cancel(true);
            releaseLineFuture = null;
        }
    }

    private void handlePlay(@NotNull JsonObject obj) {
        LOGGER.debug("Loading context (play), uri: {}", PlayCommandHelper.getContextUri(obj));

        try {
            String sessionId = state.load(obj);
            events.contextChanged();

            Boolean paused = PlayCommandHelper.isInitiallyPaused(obj);
            if (paused == null) paused = false;
            loadSession(sessionId, !paused, PlayCommandHelper.willSkipToSomething(obj));
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState(null);
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState(null);
        }
    }

    private void handleTransferState(@NotNull TransferStateOuterClass.TransferState cmd) {
        LOGGER.debug("Loading context (transfer), uri: {}", cmd.getCurrentSession().getContext().getUri());

        try {
            String sessionId = state.transfer(cmd);
            events.contextChanged();
            loadSession(sessionId, !cmd.getPlayback().getIsPaused(), true);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState(null);
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState(null);
        }
    }

    private void handleSeek(int pos) {
        playerSession.seekCurrent(pos);
        state.setPosition(pos);
        events.seeked(pos);

        PlaybackMetrics pm = metrics.get(playerSession.currentPlaybackId());
        if (pm != null) {
            pm.endInterval(state.getPosition());
            pm.startInterval(pos);
        }
    }

    private void handleResume() {
        if (state.isPaused()) {
            state.setState(true, false, false);
            sink.resume();

            state.updated();
            events.playbackResumed();

            if (releaseLineFuture != null) {
                releaseLineFuture.cancel(true);
                releaseLineFuture = null;
            }
        }
    }

    private void handlePause() {
        if (!state.isPaused()) {
            state.setState(true, true, false);
            sink.pause(false);

            try {
                if (playerSession != null)
                    state.setPosition(playerSession.currentTime());
            } catch (Codec.CannotGetTimeException ex) {
                state.setPosition(state.getPosition());
            }

            state.updated();
            events.playbackPaused();

            if (releaseLineFuture != null) releaseLineFuture.cancel(true);
            releaseLineFuture = scheduler.schedule(() -> {
                if (!state.isPaused()) return;

                events.inactiveSession(true);
                sink.pause(true);
            }, conf.releaseLineDelay, TimeUnit.SECONDS);
        }
    }

    private void handleSetQueue(@NotNull JsonObject obj) {
        List<ContextTrack> prevTracks = PlayCommandHelper.getPrevTracks(obj);
        List<ContextTrack> nextTracks = PlayCommandHelper.getNextTracks(obj);
        if (prevTracks == null && nextTracks == null) throw new IllegalArgumentException();

        state.setQueue(prevTracks, nextTracks);
        state.updated();
    }

    private void handleAddToQueue(@NotNull JsonObject obj) {
        ContextTrack track = PlayCommandHelper.getTrack(obj);
        if (track == null) throw new IllegalArgumentException();

        state.addToQueue(track);
        state.updated();
    }

    private void handleSkipNext(@Nullable JsonObject obj, @NotNull TransitionInfo trans) {
        ContextTrack track = null;
        if (obj != null) track = PlayCommandHelper.getTrack(obj);

        if (track != null) {
            state.skipTo(track);
            loadTrack(true, TransitionInfo.skipTo(state));
            return;
        }

        NextPlayable next = state.nextPlayable(conf.autoplayEnabled);
        if (next == NextPlayable.AUTOPLAY) {
            loadAutoplay();
            return;
        }

        if (next.isOk()) {
            trans.endedWhen = state.getPosition();

            state.setPosition(0);
            loadTrack(next == NextPlayable.OK_PLAY || next == NextPlayable.OK_REPEAT, trans);
        } else {
            LOGGER.fatal("Failed loading next song: " + next);
            panicState(PlaybackMetrics.Reason.END_PLAY);
        }
    }

    private void handleSkipPrev() {
        if (state.getPosition() < 3000) {
            StateWrapper.PreviousPlayable prev = state.previousPlayable();
            if (prev.isOk()) {
                state.setPosition(0);
                loadTrack(true, TransitionInfo.skippedPrev(state));
            } else {
                LOGGER.fatal("Failed loading previous song: " + prev);
                panicState(null);
            }
        } else {
            playerSession.seekCurrent(0);
            state.setPosition(0);
            state.updated();
        }
    }

    /**
     * Tries to load some additional content to play and starts playing if successful.
     */
    private void loadAutoplay() {
        String context = state.getContextUri();
        if (context == null) {
            LOGGER.fatal("Cannot load autoplay with null context!");
            panicState(null);
            return;
        }


        String contextDesc = state.getContextMetadata("context_description");

        try {
            MercuryClient.Response resp = session.mercury().sendSync(MercuryRequests.autoplayQuery(context));
            if (resp.statusCode == 200) {
                String newContext = resp.payload.readIntoString(0);
                String sessionId = state.loadContext(newContext);
                state.setContextMetadata("context_description", contextDesc);

                events.contextChanged();
                loadSession(sessionId, true, false);

                LOGGER.debug("Loading context for autoplay, uri: {}", newContext);
            } else if (resp.statusCode == 204) {
                MercuryRequests.StationsWrapper station = session.mercury().sendSync(MercuryRequests.getStationFor(context));
                String sessionId = state.loadContextWithTracks(station.uri(), station.tracks());
                state.setContextMetadata("context_description", contextDesc);

                events.contextChanged();
                loadSession(sessionId, true, false);

                LOGGER.debug("Loading context for autoplay (using radio-apollo), uri: {}", state.getContextUri());
            } else {
                LOGGER.fatal("Failed retrieving autoplay context, code: " + resp.statusCode);

                state.setPosition(0);
                state.setState(true, false, false);
                state.updated();
            }
        } catch (IOException | MercuryClient.MercuryException ex) {
            if (ex instanceof MercuryClient.MercuryException && ((MercuryClient.MercuryException) ex).code == 400) {
                LOGGER.info("Cannot load autoplay for search context: " + context);

                state.setPosition(0);
                state.setState(true, true, false);
                state.updated();
            } else {
                LOGGER.fatal("Failed loading autoplay station!", ex);
                panicState(null);
            }
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState(null);
        }
    }


    // ================================ //
    // =========== Metrics ============ //
    // ================================ //

    private void startMetrics(String playbackId, @NotNull PlaybackMetrics.Reason reason, int pos) {
        PlaybackMetrics pm = new PlaybackMetrics(state.getCurrentPlayableOrThrow(), playbackId, state);
        pm.startedHow(reason, state.getPlayOrigin().getFeatureIdentifier());
        pm.startInterval(pos);
        metrics.put(playbackId, pm);
    }

    private void endMetrics(String playbackId, @NotNull PlaybackMetrics.Reason reason, @Nullable PlayerMetrics playerMetrics, int when) {
        if (playbackId == null) return;

        PlaybackMetrics pm = metrics.remove(playbackId);
        if (pm == null) return;

        pm.endedHow(reason, state.getPlayOrigin().getFeatureIdentifier());
        pm.endInterval(when);
        pm.update(playerMetrics);
        pm.sendEvents(session, state.device());
    }


    // ================================ //
    // ======== Player events ========= //
    // ================================ //

    @Override
    public void startedLoading() {
        if (!state.isPaused()) {
            state.setBuffering(true);
            state.updated();
        }
    }

    @Override
    public void finishedLoading(@NotNull TrackOrEpisode metadata) {
        state.enrichWithMetadata(metadata);
        state.setBuffering(false);
        state.updated();

        events.metadataAvailable();
    }

    @Override
    public void sinkError(@NotNull Exception ex) {
        if (ex instanceof LineHelper.MixerException || ex instanceof LineUnavailableException) {
            LOGGER.fatal("An error with the mixer occurred. This is likely a configuration issue, please consult the project repository.", ex);
        } else {
            LOGGER.fatal("Sink error!", ex);
        }

        panicState(PlaybackMetrics.Reason.TRACK_ERROR);
    }

    @Override
    public void loadingError(@NotNull Exception ex) {
        if (ex instanceof PlayableContentFeeder.ContentRestrictedException) {
            LOGGER.error("Can't load track (content restricted).", ex);
        } else {
            LOGGER.fatal("Failed loading track.", ex);
            panicState(PlaybackMetrics.Reason.TRACK_ERROR);
        }
    }

    @Override
    public void playbackError(@NotNull Exception ex) {
        if (ex instanceof AbsChunkedInputStream.ChunkException)
            LOGGER.fatal("Failed retrieving chunk, playback failed!", ex);
        else
            LOGGER.fatal("Playback error!", ex);

        panicState(PlaybackMetrics.Reason.TRACK_ERROR);
    }

    @Override
    public void trackChanged(@NotNull String playbackId, @Nullable TrackOrEpisode metadata, int pos, @NotNull PlaybackMetrics.Reason startedReason) {
        if (metadata != null) state.enrichWithMetadata(metadata);
        state.setPlaybackId(playbackId);
        state.setPosition(pos);
        state.updated();

        events.trackChanged();
        events.metadataAvailable();

        session.eventService().sendEvent(new NewPlaybackIdEvent(state.getSessionId(), playbackId));
        startMetrics(playbackId, startedReason, pos);
    }

    @Override
    public void trackPlayed(@NotNull String playbackId, @NotNull PlaybackMetrics.Reason endReason, @NotNull PlayerMetrics playerMetrics, int when) {
        endMetrics(playbackId, endReason, playerMetrics, when);
        events.playbackEnded();
    }

    @Override
    public void playbackHalted(int chunk) {
        LOGGER.debug("Playback halted on retrieving chunk {}.", chunk);
        state.setBuffering(true);
        state.updated();

        events.playbackHaltStateChanged(true);
    }

    @Override
    public void playbackResumedFromHalt(int chunk, long diff) {
        LOGGER.debug("Playback resumed, chunk {} retrieved, took {}ms.", chunk, diff);
        state.setPosition(state.getPosition() - diff);
        state.setBuffering(false);
        state.updated();

        events.playbackHaltStateChanged(false);
    }


    // ================================ //
    // =========== Getters ============ //
    // ================================ //

    /**
     * @return Whether the player is active
     */
    public boolean isActive() {
        return state != null && state.isActive();
    }

    /**
     * @return A {@link Tracks} instance with the current player queue
     */
    @NotNull
    public Tracks tracks(boolean withQueue) {
        return new Tracks(state.getPrevTracks(), state.getCurrentTrack(), state.getNextTracks(withQueue));
    }

    /**
     * @return The metadata for the current entry or {@code null} if not available.
     */
    @Nullable
    public TrackOrEpisode currentMetadata() {
        return playerSession == null ? null : playerSession.currentMetadata();
    }

    /**
     * @return The cover image bytes for the current entry or {@code null} if not available.
     * @throws IOException If an error occurred while downloading the image
     */
    @Nullable
    public byte[] currentCoverImage() throws IOException {
        TrackOrEpisode metadata = currentMetadata();
        if (metadata == null) return null;

        ImageId image = null;
        Metadata.ImageGroup group = metadata.getCoverImage();
        if (group == null) {
            PlayableId id = state.getCurrentPlayable();
            if (id == null) return null;

            Map<String, String> map = state.metadataFor(id).orElse(null);
            if (map == null) return null;

            for (String key : ImageId.IMAGE_SIZES_URLS) {
                if (map.containsKey(key)) {
                    image = ImageId.fromUri(map.get(key));
                    break;
                }
            }
        } else {
            image = ImageId.biggestImage(group);
        }

        if (image == null)
            return null;

        try (Response resp = session.client().newCall(new Request.Builder()
                .url(session.getUserAttribute("image-url", "http://i.scdn.co/image/{file_id}").replace("{file_id}", image.hexId())).build())
                .execute()) {
            ResponseBody body;
            if (resp.code() == 200 && (body = resp.body()) != null)
                return body.bytes();
            else
                throw new IOException(String.format("Bad response code. {id: %s, code: %d}", image.hexId(), resp.code()));
        }
    }

    /**
     * @return The current content in the state
     * @throws IllegalStateException If there is no current content set
     */
    @Override
    public @NotNull PlayableId currentPlayable() {
        return state.getCurrentPlayableOrThrow();
    }

    /**
     * MUST not be called manually. This is used internally by {@link PlayerSession}.
     */
    @Override
    public @Nullable PlayableId nextPlayable() {
        NextPlayable next = state.nextPlayable(conf.autoplayEnabled);
        if (next == NextPlayable.AUTOPLAY) {
            loadAutoplay();
            return null;
        }

        if (next.isOk()) {
            if (next != NextPlayable.OK_PLAY && next != NextPlayable.OK_REPEAT)
                sink.pause(false);

            return state.getCurrentPlayableOrThrow();
        } else {
            LOGGER.fatal("Failed loading next song: " + next);
            panicState(PlaybackMetrics.Reason.END_PLAY);
            return null;
        }
    }

    /**
     * @return The next content that will be played.
     */
    @Override
    public @Nullable PlayableId nextPlayableDoNotSet() {
        return state.nextPlayableDoNotSet();
    }

    /**
     * @param playable The content
     * @return A map containing the metadata associated with this content
     */
    @Override
    public @NotNull Optional<Map<String, String>> metadataFor(@NotNull PlayableId playable) {
        return state.metadataFor(playable);
    }

    /**
     * @return The current position of the player or {@code -1} if unavailable (most likely if it's playing an episode).
     */
    public int time() {
        try {
            return playerSession == null ? -1 : playerSession.currentTime();
        } catch (Codec.CannotGetTimeException ex) {
            return -1;
        }
    }


    // ================================ //
    // ============ Close! ============ //
    // ================================ //

    @Override
    public void close() {
        if (playerSession != null) {
            endMetrics(playerSession.currentPlaybackId(), PlaybackMetrics.Reason.LOGOUT, playerSession.currentMetrics(), state.getPosition());
            playerSession.close();
        }

        state.close();
        events.listeners.clear();

        sink.close();
        if (state != null && deviceStateListener != null)
            state.removeListener(deviceStateListener);

        scheduler.shutdown();
        events.close();

        LOGGER.info("Closed player.");
    }

    public interface EventsListener {
        void onContextChanged(@NotNull String newUri);

        void onTrackChanged(@NotNull PlayableId id, @Nullable TrackOrEpisode metadata);

        void onPlaybackEnded();

        void onPlaybackPaused(long trackTime);

        void onPlaybackResumed(long trackTime);

        void onTrackSeeked(long trackTime);

        void onMetadataAvailable(@NotNull TrackOrEpisode metadata);

        void onPlaybackHaltStateChanged(boolean halted, long trackTime);

        void onInactiveSession(boolean timeout);

        void onVolumeChanged(@Range(from = 0, to = 1) float volume);

        void onPanicState();
    }

    /**
     * A simple object holding some {@link ContextTrack}s related to the current player state.
     */
    public static class Tracks {
        public final List<ContextTrack> previous;
        public final ContextTrack current;
        public final List<ContextTrack> next;

        public Tracks(@NotNull List<ContextTrack> previous, @Nullable ContextTrack current, @NotNull List<ContextTrack> next) {
            this.previous = previous;
            this.current = current;
            this.next = next;
        }
    }

    /**
     * Stores information about the transition between two tracks.
     */
    private static class TransitionInfo {
        /**
         * How the <bold>next</bold> track started
         */
        final PlaybackMetrics.Reason startedReason;

        /**
         * How the <bold>previous</bold> track ended
         */
        final PlaybackMetrics.Reason endedReason;

        /**
         * When the <bold>previous</bold> track ended
         */
        int endedWhen = -1;

        private TransitionInfo(@NotNull PlaybackMetrics.Reason endedReason, @NotNull PlaybackMetrics.Reason startedReason) {
            this.startedReason = startedReason;
            this.endedReason = endedReason;
        }

        /**
         * Context changed.
         */
        @NotNull
        static TransitionInfo contextChange(@NotNull StateWrapper state, boolean withSkip) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.END_PLAY, withSkip ? PlaybackMetrics.Reason.CLICK_ROW : PlaybackMetrics.Reason.PLAY_BTN);
            if (state.getCurrentPlayable() != null) trans.endedWhen = state.getPosition();
            return trans;
        }

        /**
         * Skipping to another track in the same context.
         */
        @NotNull
        static TransitionInfo skipTo(@NotNull StateWrapper state) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.END_PLAY, PlaybackMetrics.Reason.CLICK_ROW);
            if (state.getCurrentPlayable() != null) trans.endedWhen = state.getPosition();
            return trans;
        }

        /**
         * Skipping to previous track.
         */
        @NotNull
        static TransitionInfo skippedPrev(@NotNull StateWrapper state) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.BACK_BTN, PlaybackMetrics.Reason.BACK_BTN);
            if (state.getCurrentPlayable() != null) trans.endedWhen = state.getPosition();
            return trans;
        }

        /**
         * Skipping to next track.
         */
        @NotNull
        static TransitionInfo skippedNext(@NotNull StateWrapper state) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.FORWARD_BTN, PlaybackMetrics.Reason.FORWARD_BTN);
            if (state.getCurrentPlayable() != null) trans.endedWhen = state.getPosition();
            return trans;
        }
    }

    private class EventsDispatcher {
        private final MetadataPipe metadataPipe;
        private final ExecutorService executorService = Executors.newSingleThreadExecutor(new NameThreadFactory((r) -> "player-events-" + r.hashCode()));
        private final List<EventsListener> listeners = new ArrayList<>();

        EventsDispatcher(@NotNull PlayerConfiguration conf) {
            metadataPipe = new MetadataPipe(conf);
        }

        private void sendImage() {
            byte[] image;
            try {
                image = currentCoverImage();
            } catch (IOException ex) {
                LOGGER.warn("Failed downloading image.", ex);
                return;
            }

            if (image == null) {
                LOGGER.warn("No image found in metadata.");
                return;
            }

            metadataPipe.safeSend(MetadataPipe.TYPE_SSNC, MetadataPipe.CODE_PICT, image);
        }

        private void sendProgress() {
            TrackOrEpisode metadata = currentMetadata();
            if (metadata == null) return;

            String data = String.format("1/%.0f/%.0f", state.getPosition() * AudioSink.DEFAULT_FORMAT.getSampleRate() / 1000 + 1,
                    metadata.duration() * AudioSink.DEFAULT_FORMAT.getSampleRate() / 1000 + 1);
            metadataPipe.safeSend(MetadataPipe.TYPE_SSNC, MetadataPipe.CODE_PRGR, data);
        }

        private void sendTrackInfo() {
            TrackOrEpisode metadata = currentMetadata();
            if (metadata == null) return;

            metadataPipe.safeSend(MetadataPipe.TYPE_CORE, MetadataPipe.CODE_MINM, metadata.getName());
            metadataPipe.safeSend(MetadataPipe.TYPE_CORE, MetadataPipe.CODE_ASAL, metadata.getAlbumName());
            metadataPipe.safeSend(MetadataPipe.TYPE_CORE, MetadataPipe.CODE_ASAR, metadata.getArtist());
        }

        private void sendVolume(int value) {
            float xmlValue;
            if (value == 0) xmlValue = -144.0f;
            else xmlValue = (value - Player.VOLUME_MAX) * 30.0f / (Player.VOLUME_MAX - 1);
            String volData = String.format("%.2f,0.00,0.00,0.00", xmlValue);
            metadataPipe.safeSend(MetadataPipe.TYPE_SSNC, MetadataPipe.CODE_PVOL, volData);
        }

        void playbackEnded() {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(l::onPlaybackEnded);
        }

        void playbackPaused() {
            long trackTime = state.getPosition();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackPaused(trackTime));
        }

        void playbackResumed() {
            long trackTime = state.getPosition();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackResumed(trackTime));
            if (metadataPipe.enabled()) {
                executorService.execute(() -> {
                    sendTrackInfo();
                    sendProgress();
                    sendImage();
                });
            }
        }

        void contextChanged() {
            String uri = state.getContextUri();
            if (uri == null) return;

            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onContextChanged(uri));
        }

        void trackChanged() {
            PlayableId id = state.getCurrentPlayable();
            if (id == null) return;

            TrackOrEpisode metadata = currentMetadata();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onTrackChanged(id, metadata));
        }

        void seeked(int pos) {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onTrackSeeked(pos));

            if (metadataPipe.enabled()) executorService.execute(this::sendProgress);
        }

        void volumeChanged(@Range(from = 0, to = Player.VOLUME_MAX) int value) {
            float volume = (float) value / Player.VOLUME_MAX;

            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onVolumeChanged(volume));

            if (metadataPipe.enabled()) executorService.execute(() -> sendVolume(value));
        }

        void metadataAvailable() {
            TrackOrEpisode metadata = currentMetadata();
            if (metadata == null) return;

            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onMetadataAvailable(metadata));

            if (metadataPipe.enabled()) {
                executorService.execute(() -> {
                    sendTrackInfo();
                    sendProgress();
                    sendImage();
                });
            }
        }

        void playbackHaltStateChanged(boolean halted) {
            long trackTime = state.getPosition();
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onPlaybackHaltStateChanged(halted, trackTime));
        }

        void inactiveSession(boolean timeout) {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(() -> l.onInactiveSession(timeout));
        }

        private void panicState() {
            for (EventsListener l : new ArrayList<>(listeners))
                executorService.execute(l::onPanicState);
        }

        public void close() {
            executorService.shutdown();
        }
    }
}
