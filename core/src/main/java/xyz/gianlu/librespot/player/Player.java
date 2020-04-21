package xyz.gianlu.librespot.player;

import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.spotify.context.ContextTrackOuterClass.ContextTrack;
import com.spotify.metadata.Metadata;
import com.spotify.transfer.TransferStateOuterClass;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler;
import xyz.gianlu.librespot.connectstate.DeviceStateHandler.PlayCommandHelper;
import xyz.gianlu.librespot.core.EventService;
import xyz.gianlu.librespot.core.EventService.PlaybackMetrics;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.ImageId;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.StateWrapper.NextPlayable;
import xyz.gianlu.librespot.player.codecs.AudioQuality;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.contexts.AbsSpotifyContext;
import xyz.gianlu.librespot.player.mixing.AudioSink;
import xyz.gianlu.librespot.player.queue.PlayerQueue;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author Gianlu
 */
public class Player implements Closeable, DeviceStateHandler.Listener, PlayerQueue.Listener { // TODO: Reduce calls to state update
    public static final int VOLUME_STEPS = 64;
    public static final int VOLUME_MAX = 65536;
    public static final int VOLUME_ONE_STEP = VOLUME_MAX / VOLUME_STEPS;
    private static final Logger LOGGER = Logger.getLogger(Player.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NameThreadFactory((r) -> "release-line-scheduler-" + r.hashCode()));
    private final Session session;
    private final Configuration conf;
    private final PlayerQueue queue;
    private final EventsDispatcher events;
    private StateWrapper state;
    private ScheduledFuture<?> releaseLineFuture = null;
    private PlaybackMetrics playbackMetrics = null;

    public Player(@NotNull Player.Configuration conf, @NotNull Session session) {
        this.conf = conf;
        this.session = session;
        this.events = new EventsDispatcher(conf);
        this.queue = new PlayerQueue(session, conf, this);
    }

    public void addEventsListener(@NotNull EventsListener listener) {
        events.listeners.add(listener);
    }

    public void removeEventsListener(@NotNull EventsListener listener) {
        events.listeners.remove(listener);
    }


    // ================================ //
    // =========== Commands =========== //
    // ================================ //

    public void initState() {
        this.state = new StateWrapper(session);
        state.addListener(this);
    }

    public void volumeUp() {
        if (state == null) return;
        setVolume(Math.min(PlayerRunner.VOLUME_MAX, state.getVolume() + oneVolumeStep()));
    }

    public void volumeDown() {
        if (state == null) return;
        setVolume(Math.max(0, state.getVolume() - oneVolumeStep()));
    }

    private int oneVolumeStep() {
        return PlayerRunner.VOLUME_MAX / conf.volumeSteps();
    }

    public void setVolume(int val) {
        if (val < 0 || val > VOLUME_MAX)
            throw new IllegalArgumentException(String.valueOf(val));

        events.volumeChanged(val);

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
        handleNext(null, TransitionInfo.skippedNext(state));
    }

    public void previous() {
        handlePrev();
    }

    public void load(@NotNull String uri, boolean play) {
        try {
            state.loadContext(uri);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState(null);
            return;
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState(null);
            return;
        }

        events.contextChanged();
        loadTrack(play, TransitionInfo.contextChange(state, true));
    }


    // ================================ //
    // ======== Internal state ======== //
    // ================================ //

    private void transferState(TransferStateOuterClass.@NotNull TransferState cmd) {
        LOGGER.debug(String.format("Loading context (transfer), uri: %s", cmd.getCurrentSession().getContext().getUri()));

        try {
            state.transfer(cmd);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState(null);
            return;
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState(null);
            return;
        }

        events.contextChanged();
        loadTrack(!cmd.getPlayback().getIsPaused(), TransitionInfo.contextChange(state, true));

        session.eventService().newSessionId(state);
        session.eventService().newPlaybackId(state);
    }

    private void handleLoad(@NotNull JsonObject obj) {
        LOGGER.debug(String.format("Loading context (play), uri: %s", PlayCommandHelper.getContextUri(obj)));

        try {
            state.load(obj);
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading context!", ex);
            panicState(null);
            return;
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState(null);
            return;
        }

        events.contextChanged();

        Boolean paused = PlayCommandHelper.isInitiallyPaused(obj);
        if (paused == null) paused = true;
        loadTrack(!paused, TransitionInfo.contextChange(state, PlayCommandHelper.willSkipToSomething(obj)));
    }

    private void panicState(@Nullable EventService.PlaybackMetrics.Reason reason) {
        queue.pause(true);
        state.setState(false, false, false);
        state.updated();

        if (reason != null && playbackMetrics != null && queue.isCurrent(playbackMetrics.id)) {
            playbackMetrics.endedHow(reason, null);
            playbackMetrics.endInterval(state.getPosition());
            session.eventService().trackPlayed(state, playbackMetrics);
            playbackMetrics = null;
        }
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
                handleNext(data.obj(), TransitionInfo.skippedNext(state));
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
        queue.setVolume(state.getVolume());
    }

    @Override
    public void notActive() {
        events.inactiveSession(false);
        queue.pause(true);
    }

    private void entryIsReady() {
        if (playbackMetrics != null && queue.isCurrent(playbackMetrics.id))
            playbackMetrics.update(queue.currentMetrics());

        TrackOrEpisode metadata = currentMetadata();
        if (metadata != null) {
            state.enrichWithMetadata(metadata);
            events.metadataAvailable();
        }
    }

    private void handleSeek(int pos) {
        if (playbackMetrics != null && queue.isCurrent(playbackMetrics.id)) {
            playbackMetrics.endInterval(state.getPosition());
            playbackMetrics.startInterval(pos);
        }

        state.setPosition(pos);
        queue.seekCurrent(pos);
        events.seeked(pos);
    }

    private void handleResume() {
        if (state.isPaused()) {
            state.setState(true, false, false);
            queue.resume();

            state.updated();
            events.playbackResumed();

            if (releaseLineFuture != null) {
                releaseLineFuture.cancel(true);
                releaseLineFuture = null;
            }
        }
    }

    private void handlePause() {
        if (state.isPlaying()) {
            state.setState(true, true, false);
            queue.pause(false);

            try {
                state.setPosition(queue.currentTime());
            } catch (Codec.CannotGetTimeException ignored) {
            }

            state.updated();
            events.playbackPaused();

            if (releaseLineFuture != null) releaseLineFuture.cancel(true);
            releaseLineFuture = scheduler.schedule(() -> {
                if (!state.isPaused()) return;

                events.inactiveSession(true);
                queue.pause(true);
            }, conf.releaseLineDelay(), TimeUnit.SECONDS);
        }
    }

    private void loadTrack(boolean play, @NotNull TransitionInfo trans) {
        if (playbackMetrics != null && queue.isCurrent(playbackMetrics.id)) {
            playbackMetrics.endedHow(trans.endedReason, state.getPlayOrigin().getFeatureIdentifier());
            playbackMetrics.endInterval(trans.endedWhen);
            session.eventService().trackPlayed(state, playbackMetrics);
            playbackMetrics = null;
        }

        state.renewPlaybackId();

        PlayableId playable = state.getCurrentPlayableOrThrow();
        playbackMetrics = new PlaybackMetrics(playable);
        if (!queue.isCurrent(playable)) {
            queue.clear();

            state.setState(true, !play, true);
            int id = queue.load(playable);
            queue.follows(id);
            queue.seek(id, state.getPosition());

            state.updated();
            events.trackChanged();

            if (play) {
                queue.resume();
                events.playbackResumed();
            } else {
                queue.pause(false);
                events.playbackPaused();
            }
        } else {
            entryIsReady();

            state.updated();
            events.trackChanged();

            if (!play) queue.pause(false);
        }

        if (releaseLineFuture != null) {
            releaseLineFuture.cancel(true);
            releaseLineFuture = null;
        }

        playbackMetrics.startedHow(trans.startedReason, state.getPlayOrigin().getFeatureIdentifier());
        playbackMetrics.startInterval(state.getPosition());
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

    private void handleNext(@Nullable JsonObject obj, @NotNull TransitionInfo trans) {
        ContextTrack track = null;
        if (obj != null) track = PlayCommandHelper.getTrack(obj);

        if (track != null) {
            state.skipTo(track);
            loadTrack(true, TransitionInfo.skipTo(state));
            return;
        }

        NextPlayable next = state.nextPlayable(conf);
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

    private void handlePrev() {
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
            state.setPosition(0);
            queue.seekCurrent(0);
            state.updated();
        }
    }

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
                state.loadContext(newContext);
                state.setContextMetadata("context_description", contextDesc);

                events.contextChanged();
                loadTrack(true, TransitionInfo.contextChange(state, false));

                LOGGER.debug(String.format("Loading context for autoplay, uri: %s", newContext));
            } else if (resp.statusCode == 204) {
                MercuryRequests.StationsWrapper station = session.mercury().sendSync(MercuryRequests.getStationFor(context));
                state.loadContextWithTracks(station.uri(), station.tracks());
                state.setContextMetadata("context_description", contextDesc);

                events.contextChanged();
                loadTrack(true, TransitionInfo.contextChange(state, false));

                LOGGER.debug(String.format("Loading context for autoplay (using radio-apollo), uri: %s", state.getContextUri()));
            } else {
                LOGGER.fatal("Failed retrieving autoplay context, code: " + resp.statusCode);

                state.setPosition(0);
                state.setState(true, false, false);
                state.updated();
            }
        } catch (IOException | MercuryClient.MercuryException ex) {
            LOGGER.fatal("Failed loading autoplay station!", ex);
            panicState(null);
        } catch (AbsSpotifyContext.UnsupportedContextException ex) {
            LOGGER.fatal("Cannot play local tracks!", ex);
            panicState(null);
        }
    }


    // ================================ //
    // ======== Player events ========= //
    // ================================ //

    @Override
    public void startedLoading(int id) {
        if (queue.isCurrent(id)) {
            if (!state.isBuffering()) {
                state.setBuffering(true);
                state.updated();
            }
        }
    }

    @Override
    public void finishedLoading(int id) {
        if (queue.isCurrent(id)) {
            state.setBuffering(false);
            entryIsReady();
            state.updated();
        } else if (queue.isNext(id)) {
            LOGGER.trace("Preloaded track is ready.");
        }
    }

    @Override
    public void sinkError(@NotNull Exception ex) {
        LOGGER.fatal("Sink error!", ex);
        panicState(PlaybackMetrics.Reason.TRACK_ERROR);
    }

    @Override
    public void loadingError(int id, @NotNull PlayableId playable, @NotNull Exception ex) {
        if (queue.isCurrent(id)) {
            if (ex instanceof ContentRestrictedException) {
                LOGGER.fatal(String.format("Can't load track (content restricted). {uri: %s}", playable.toSpotifyUri()), ex);
                handleNext(null, TransitionInfo.nextError(state));
                return;
            }

            LOGGER.fatal(String.format("Failed loading track. {uri: %s}", playable.toSpotifyUri()), ex);
            panicState(PlaybackMetrics.Reason.TRACK_ERROR);
        }
    }

    @Override
    public void endOfPlayback(int id) {
        if (queue.isCurrent(id)) {
            LOGGER.trace(String.format("End of track. {id: %d}", id));
            handleNext(null, TransitionInfo.next(state));
        }
    }

    @Override
    public void startedNextTrack(int id, int next) {
        if (queue.isCurrent(next)) {
            LOGGER.trace(String.format("Playing next track. {id: %d}", next));
            handleNext(null, TransitionInfo.next(state));
        }
    }

    @Override
    public void preloadNextTrack(int id) {
        if (queue.isCurrent(id)) {
            PlayableId next = state.nextPlayableDoNotSet();
            if (next != null) {
                int nextId = queue.load(next);
                queue.follows(nextId);
                LOGGER.trace("Started next track preload, uri: " + next.toSpotifyUri());
            }
        }
    }

    @Override
    public void finishedSeek(int id, int pos) {
        if (queue.isCurrent(id)) {
            state.setPosition(pos);
            state.updated();
        }
    }

    @Override
    public void playbackError(int id, @NotNull Exception ex) {
        if (queue.isCurrent(id)) {
            if (ex instanceof AbsChunkedInputStream.ChunkException)
                LOGGER.fatal("Failed retrieving chunk, playback failed!", ex);
            else
                LOGGER.fatal("Playback error!", ex);

            panicState(PlaybackMetrics.Reason.TRACK_ERROR);
        } else if (queue.isNext(id)) {
            LOGGER.warn("Preloaded track loading failed!", ex);
        }
    }

    @Override
    public void playbackHalted(int id, int chunk) {
        if (queue.isCurrent(id)) {
            LOGGER.debug(String.format("Playback halted on retrieving chunk %d.", chunk));

            state.setBuffering(true);
            state.updated();

            events.playbackHaltStateChanged(true);
        }
    }

    @Override
    public void playbackResumedFromHalt(int id, int chunk, long diff) {
        if (queue.isCurrent(id)) {
            LOGGER.debug(String.format("Playback resumed, chunk %d retrieved, took %dms.", chunk, diff));

            state.setPosition(state.getPosition() - diff);
            state.setBuffering(false);
            state.updated();

            events.playbackHaltStateChanged(false);
        }
    }


    // ================================ //
    // =========== Getters ============ //
    // ================================ //

    /**
     * @return Whether the player is active
     */
    public boolean isActive() {
        return state.isActive();
    }

    @Nullable
    public TrackOrEpisode currentMetadata() {
        return queue.currentMetadata();
    }

    @Nullable
    public PlayableId currentPlayableId() {
        return state.getCurrentPlayable();
    }

    @Nullable
    public byte[] currentCoverImage() throws IOException {
        TrackOrEpisode metadata = currentMetadata();
        if (metadata == null) return null;

        ImageId image = null;
        Metadata.ImageGroup group = metadata.getCoverImage();
        if (group == null) {
            PlayableId id = state.getCurrentPlayable();
            if (id == null) return null;

            Map<String, String> map = state.metadataFor(id);
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
     * @return The current position of the player or {@code -1} if unavailable (most likely if it's playing an episode).
     */
    public long time() {
        try {
            return queue.currentTime();
        } catch (Codec.CannotGetTimeException ex) {
            return -1;
        }
    }

    // ================================ //
    // ============ Close! ============ //
    // ================================ //

    @Override
    public void close() {
        if (playbackMetrics != null && queue.isCurrent(playbackMetrics.id)) {
            playbackMetrics.endedHow(PlaybackMetrics.Reason.LOGOUT, null);
            playbackMetrics.endInterval(state.getPosition());
            session.eventService().trackPlayed(state, playbackMetrics);
            playbackMetrics = null;
        }

        state.close();
        events.listeners.clear();

        queue.close();
        if (state != null) state.removeListener(this);

        scheduler.shutdown();
        events.close();
    }

    public interface Configuration {
        @NotNull
        AudioQuality preferredQuality();

        @NotNull
        AudioOutput output();

        @Nullable
        File outputPipe();

        @Nullable
        File metadataPipe();

        boolean preloadEnabled();

        boolean enableNormalisation();

        float normalisationPregain();

        @Nullable
        String[] mixerSearchKeywords();

        boolean logAvailableMixers();

        int initialVolume();

        int volumeSteps();

        boolean autoplayEnabled();

        int crossfadeDuration();

        int releaseLineDelay();

        boolean stopPlaybackOnChunkError();
    }

    public interface EventsListener {
        void onContextChanged(@NotNull String newUri);

        void onTrackChanged(@NotNull PlayableId id, @Nullable TrackOrEpisode metadata);

        void onPlaybackPaused(long trackTime);

        void onPlaybackResumed(long trackTime);

        void onTrackSeeked(long trackTime);

        void onMetadataAvailable(@NotNull TrackOrEpisode metadata);

        void onPlaybackHaltStateChanged(boolean halted, long trackTime);

        void onInactiveSession(boolean timeout);

        void onVolumeChanged(@Range(from = 0, to = 1) float volume);
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

        private TransitionInfo(@NotNull EventService.PlaybackMetrics.Reason endedReason, @NotNull EventService.PlaybackMetrics.Reason startedReason) {
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
         * Proceeding to the next track without user intervention.
         */
        @NotNull
        static TransitionInfo next(@NotNull StateWrapper state) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.TRACK_DONE, PlaybackMetrics.Reason.TRACK_DONE);
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

        /**
         * Skipping to next track due to an error.
         */
        @NotNull
        static TransitionInfo nextError(@NotNull StateWrapper state) {
            TransitionInfo trans = new TransitionInfo(PlaybackMetrics.Reason.TRACK_ERROR, PlaybackMetrics.Reason.TRACK_ERROR);
            if (state.getCurrentPlayable() != null) trans.endedWhen = state.getPosition();
            return trans;
        }
    }

    private static class MetadataPipe {
        private static final String TYPE_SSNC = "73736e63";
        private static final String TYPE_CORE = "636f7265";
        private static final String CODE_ASAR = "61736172";
        private static final String CODE_ASAL = "6173616c";
        private static final String CODE_MINM = "6d696e6d";
        private static final String CODE_PVOL = "70766f6c";
        private static final String CODE_PRGR = "70726772";
        private static final String CODE_PICT = "50494354";
        private final File file;
        private FileOutputStream out;

        MetadataPipe(@NotNull Configuration conf) {
            file = conf.metadataPipe();
        }

        void safeSend(@NotNull String type, @NotNull String code, @Nullable String payload) {
            try {
                send(type, code, payload == null ? null : payload.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ex) {
                LOGGER.error("Failed sending metadata through pipe!", ex);
            }
        }

        void safeSend(@NotNull String type, @NotNull String code, @Nullable byte[] payload) {
            try {
                send(type, code, payload);
            } catch (IOException ex) {
                LOGGER.error("Failed sending metadata through pipe!", ex);
            }
        }

        private synchronized void send(@NotNull String type, @NotNull String code, @Nullable byte[] payload) throws IOException {
            if (file == null) return;
            if (out == null) out = new FileOutputStream(file);

            if (payload != null) {
                out.write(String.format("<item><type>%s</type><code>%s</code><length>%d</length>\n<data encoding=\"base64\">%s</data></item>\n", type, code,
                        payload.length, new String(Base64.getEncoder().encode(payload), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            } else {
                out.write(String.format("<item><type>%s</type><code>%s</code><length>0</length></item>\n", type, code).getBytes(StandardCharsets.UTF_8));
            }
        }

        boolean enabled() {
            return file != null;
        }
    }

    private class EventsDispatcher {
        private final MetadataPipe metadataPipe;
        private final ExecutorService executorService = Executors.newSingleThreadExecutor(new NameThreadFactory((r) -> "player-events-" + r.hashCode()));
        private final List<EventsListener> listeners = new ArrayList<>();

        EventsDispatcher(@NotNull Configuration conf) {
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

            String data = String.format("1/%.0f/%.0f", state.getPosition() * AudioSink.OUTPUT_FORMAT.getSampleRate() / 1000 + 1,
                    metadata.duration() * AudioSink.OUTPUT_FORMAT.getSampleRate() / 1000 + 1);
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
            if (value == 0) xmlValue = 144.0f;
            else xmlValue = (value - Player.VOLUME_MAX) * 30.0f / (Player.VOLUME_MAX - 1);
            String volData = String.format("%.2f,0.00,0.00,0.00", xmlValue);
            metadataPipe.safeSend(MetadataPipe.TYPE_SSNC, MetadataPipe.CODE_PVOL, volData);
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

        public void close() {
            executorService.shutdown();
        }
    }
}
