package xyz.gianlu.librespot.player.playback;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.core.EventService.PlaybackMetrics.Reason;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.ContentRestrictedException;
import xyz.gianlu.librespot.player.TrackOrEpisode;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.crossfade.CrossfadeController;
import xyz.gianlu.librespot.player.mixing.AudioSink;
import xyz.gianlu.librespot.player.mixing.MixingLine;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles a session which is a container for entries (each with its own playback ID). This is responsible for higher level prev/next operations (using {@link PlayerQueue},
 * receiving and creating instants, dispatching events to the player and operating the sink.
 *
 * @author devgianlu
 */
public class PlayerSession implements Closeable, PlayerQueueEntry.Listener {
    private static final Logger LOGGER = Logger.getLogger(PlayerSession.class);
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory((r) -> "player-session-" + r.hashCode()));
    private final Session session;
    private final AudioSink sink;
    private final String sessionId;
    private final Listener listener;
    private final PlayerQueue queue;
    private int lastPlayPos = 0;
    private Reason lastPlayReason = null;
    private volatile boolean closed = false;

    public PlayerSession(@NotNull Session session, @NotNull AudioSink sink, @NotNull String sessionId, @NotNull Listener listener) {
        this.session = session;
        this.sink = sink;
        this.sessionId = sessionId;
        this.listener = listener;
        this.queue = new PlayerQueue();
        LOGGER.info(String.format("Created new session. {id: %s}", sessionId));

        sink.clearOutputs();
        add(listener.currentPlayable(), false);
    }

    /**
     * Creates and adds a new entry to the queue.
     *
     * @param playable The content for the new entry
     */
    private void add(@NotNull PlayableId playable, boolean preloaded) {
        queue.add(new PlayerQueueEntry(session, sink.getFormat(), playable, preloaded, this));
    }

    /**
     * Adds the next content to the queue.
     */
    private void addNext() {
        PlayableId playable = listener.nextPlayableDoNotSet();
        if (playable != null) add(playable, true);
    }

    /**
     * Tries to advance to the given content. This is a destructive operation as it will close every entry that passes by.
     *
     * @param id The target content
     * @return Whether the operation was successful
     */
    private boolean advanceTo(@NotNull PlayableId id) {
        do {
            PlayerQueueEntry entry = queue.head();
            if (entry == null) return false;
            if (entry.playable.equals(id)) return true;
        } while (queue.advance());
        return false;
    }

    /**
     * Gets the next content and tries to advance, notifying if successful.
     */
    private void advance(@NotNull Reason reason) {
        if (closed) return;

        PlayableId next = listener.nextPlayable();
        if (next == null)
            return;

        EntryWithPos entry = playInternal(next, 0, reason);
        listener.trackChanged(entry.entry.playbackId, entry.entry.metadata(), entry.pos, reason);
    }

    @Override
    public void instantReached(@NotNull PlayerQueueEntry entry, int callbackId, int exactTime) {
        switch (callbackId) {
            case PlayerQueueEntry.INSTANT_PRELOAD:
                if (entry == queue.head()) executorService.execute(this::addNext);
                break;
            case PlayerQueueEntry.INSTANT_START_NEXT:
                executorService.execute(() -> advance(Reason.TRACK_DONE));
                break;
            case PlayerQueueEntry.INSTANT_END:
                entry.close();
                break;
            default:
                throw new IllegalArgumentException("Unknown callback: " + callbackId);
        }
    }

    @Override
    public void playbackEnded(@NotNull PlayerQueueEntry entry) {
        listener.trackPlayed(entry.playbackId, entry.endReason, entry.metrics(), entry.getTimeNoThrow());

        if (entry == queue.head())
            advance(Reason.TRACK_DONE);
    }

    @Override
    public void startedLoading(@NotNull PlayerQueueEntry entry) {
        LOGGER.trace(String.format("%s started loading.", entry));
        if (entry == queue.head()) listener.startedLoading();
    }

    @Override
    public void loadingError(@NotNull PlayerQueueEntry entry, @NotNull Exception ex, boolean retried) {
        if (entry == queue.head()) {
            if (ex instanceof ContentRestrictedException) {
                advance(Reason.TRACK_ERROR);
            } else if (!retried) {
                PlayerQueueEntry newEntry = entry.retrySelf(false);
                executorService.execute(() -> {
                    queue.swap(entry, newEntry);
                    playInternal(newEntry.playable, lastPlayPos, lastPlayReason == null ? Reason.TRACK_ERROR : lastPlayReason);
                });
                return;
            }

            listener.loadingError(ex);
        } else if (entry == queue.next()) {
            if (!(ex instanceof ContentRestrictedException) && !retried) {
                PlayerQueueEntry newEntry = entry.retrySelf(true);
                executorService.execute(() -> queue.swap(entry, newEntry));
                return;
            }
        }

        queue.remove(entry);
    }

    @Override
    public void finishedLoading(@NotNull PlayerQueueEntry entry, @NotNull TrackOrEpisode metadata) {
        LOGGER.trace(String.format("%s finished loading.", entry));
        if (entry == queue.head()) listener.finishedLoading(metadata);

        CrossfadeController.FadeInterval fadeOut;
        if (entry.crossfade != null && (fadeOut = entry.crossfade.selectFadeOut(Reason.TRACK_DONE)) != null)
            entry.notifyInstant(PlayerQueueEntry.INSTANT_START_NEXT, fadeOut.start());
    }

    @Override
    public @NotNull Map<String, String> metadataFor(@NotNull PlayableId playable) {
        return listener.metadataFor(playable);
    }

    @Override
    public void playbackError(@NotNull PlayerQueueEntry entry, @NotNull Exception ex) {
        if (entry == queue.head()) listener.playbackError(ex);
        queue.remove(entry);
    }

    @Override
    public void playbackHalted(@NotNull PlayerQueueEntry entry, int chunk) {
        if (entry == queue.head()) listener.playbackHalted(chunk);
    }

    @Override
    public void playbackResumed(@NotNull PlayerQueueEntry entry, int chunk, int diff) {
        if (entry == queue.head()) listener.playbackResumedFromHalt(chunk, diff);
    }


    // ================================ //
    // =========== Playback =========== //
    // ================================ //

    /**
     * Start playing this content by any possible mean. Also sets up crossfade for the previous entry and the current head.
     *
     * @param playable The content to be played
     * @param pos      The time in milliseconds
     * @param reason   The reason why the playback started
     */
    @Contract("_, _, _ -> new")
    private @NotNull EntryWithPos playInternal(@NotNull PlayableId playable, int pos, @NotNull Reason reason) {
        lastPlayPos = pos;
        lastPlayReason = reason;

        if (!advanceTo(playable)) {
            add(playable, false);
            queue.advance();
        }

        PlayerQueueEntry head = queue.head();
        if (head == null)
            throw new IllegalStateException();

        if (head.prev != null) {
            head.prev.endReason = reason;
            CrossfadeController.FadeInterval fadeOut;
            if (head.prev.crossfade == null || (fadeOut = head.prev.crossfade.selectFadeOut(reason)) == null) {
                head.prev.close();
            } else {
                if (fadeOut instanceof CrossfadeController.PartialFadeInterval) {
                    try {
                        int time = head.prev.getTime();
                        head.prev.notifyInstant(PlayerQueueEntry.INSTANT_END, ((CrossfadeController.PartialFadeInterval) fadeOut).end(time));
                    } catch (Codec.CannotGetTimeException ex) {
                        head.prev.close();
                    }
                } else {
                    head.prev.notifyInstant(PlayerQueueEntry.INSTANT_END, fadeOut.end());
                }
            }
        }

        MixingLine.MixingOutput out = sink.someOutput();
        if (out == null)
            throw new IllegalStateException("No output is available for " + head);

        CrossfadeController.FadeInterval fadeIn;
        if (head.crossfade != null && (fadeIn = head.crossfade.selectFadeIn(reason)) != null) {
            head.seek(pos = fadeIn.start());
        } else {
            head.seek(pos);
        }

        head.setOutput(out);
        LOGGER.debug(String.format("%s has been added to the output. {sessionId: %s, pos: %d, reason: %s}", head, sessionId, pos, reason));
        return new PlayerSession.EntryWithPos(head, pos);
    }

    /**
     * Start playing this content by any possible mean. Also sets up crossfade for the previous entry and the current head.
     *
     * @param playable The content to be played
     * @param pos      The time in milliseconds
     * @param reason   The reason why the playback started
     * @return The playback ID associated with the head
     */
    @NotNull
    public String play(@NotNull PlayableId playable, int pos, @NotNull Reason reason) {
        return playInternal(playable, pos, reason).entry.playbackId;
    }

    /**
     * Seek to the specified position on the queue head.
     *
     * @param pos The time in milliseconds
     */
    public void seekCurrent(int pos) {
        if (queue.head() == null) return;

        if (queue.prev() != null) queue.remove(queue.prev());
        if (queue.next() != null) queue.remove(queue.next());

        queue.head().seek(pos);
        sink.flush();
    }


    // ================================ //
    // =========== Getters ============ //
    // ================================ //

    /**
     * @return The {@link PlayerMetrics} for the current entry or {@code null} if not available.
     */
    @Nullable
    public PlayerMetrics currentMetrics() {
        if (queue.head() == null) return null;
        else return queue.head().metrics();
    }

    /**
     * @return The metadata for the current head or {@code null} if not available.
     */
    @Nullable
    public TrackOrEpisode currentMetadata() {
        if (queue.head() == null) return null;
        else return queue.head().metadata();
    }

    /**
     * @return The time for the current head or {@code -1} if not available.
     * @throws Codec.CannotGetTimeException If the head is available, but time cannot be retrieved
     */
    public long currentTime() throws Codec.CannotGetTimeException {
        if (queue.head() == null) return -1;
        else return queue.head().getTime();
    }

    @Nullable
    public String currentPlaybackId() {
        if (queue.head() == null) return null;
        else return queue.head().playbackId;
    }

    /**
     * Close the session by clearing the queue which will close all entries.
     */
    @Override
    public void close() {
        closed = true;
        queue.close();
    }

    public interface Listener {
        @NotNull
        PlayableId currentPlayable();

        @Nullable
        PlayableId nextPlayable();

        @Nullable
        PlayableId nextPlayableDoNotSet();

        /**
         * Get the metadata for this content.
         *
         * @param playable The content
         * @return A map containing all the metadata related
         */
        @NotNull
        Map<String, String> metadataFor(@NotNull PlayableId playable);

        /**
         * The current track playback halted while trying to receive a chunk.
         *
         * @param chunk The chunk that is being retrieved
         */
        void playbackHalted(int chunk);

        /**
         * The current track playback resumed from halt.
         *
         * @param chunk The chunk that was being retrieved
         * @param diff  The time taken to retrieve the chunk
         */
        void playbackResumedFromHalt(int chunk, long diff);

        /**
         * The current track started loading.
         */
        void startedLoading();

        /**
         * The current track failed loading.
         *
         * @param ex The exception thrown
         */
        void loadingError(@NotNull Exception ex);

        /**
         * The current track finished loading.
         *
         * @param metadata The {@link TrackOrEpisode} object
         */
        void finishedLoading(@NotNull TrackOrEpisode metadata);

        /**
         * An error occurred during playback of the current track.
         *
         * @param ex The exception thrown
         */
        void playbackError(@NotNull Exception ex);

        /**
         * The current track changed. Not called if {@link PlayerSession#playInternal(PlayableId, int, Reason)} is called directly.
         *
         * @param playbackId    The new playback ID
         * @param metadata      The metadata for the new track
         * @param pos           The position at which playback started
         * @param startedReason The reason why the current track changed
         */
        void trackChanged(@NotNull String playbackId, @Nullable TrackOrEpisode metadata, int pos, @NotNull Reason startedReason);

        /**
         * The current entry has finished playing.
         *
         * @param playbackId    The playback ID of this entry
         * @param endReason     The reason why this track ended
         * @param playerMetrics The {@link PlayerMetrics} for this entry
         * @param endedAt       The time this entry ended
         */
        void trackPlayed(@NotNull String playbackId, @NotNull Reason endReason, @NotNull PlayerMetrics playerMetrics, int endedAt);
    }

    private static class EntryWithPos {
        final PlayerQueueEntry entry;
        final int pos;

        EntryWithPos(@NotNull PlayerQueueEntry entry, int pos) {
            this.entry = entry;
            this.pos = pos;
        }
    }
}
