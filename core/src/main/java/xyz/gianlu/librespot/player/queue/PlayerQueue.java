package xyz.gianlu.librespot.player.queue;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.ContentRestrictedException;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.TrackOrEpisode;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.codecs.Mp3Codec;
import xyz.gianlu.librespot.player.codecs.VorbisCodec;
import xyz.gianlu.librespot.player.feeders.cdn.CdnManager;
import xyz.gianlu.librespot.player.mixing.AudioSink;
import xyz.gianlu.librespot.player.mixing.MixingLine;

import javax.sound.sampled.AudioFormat;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class PlayerQueue implements Closeable, QueueEntry.@NotNull Listener {
    static final int INSTANT_START_NEXT = 2;
    static final int INSTANT_END_NOW = 3;
    private static final int INSTANT_PRELOAD = 1;
    private static final Logger LOGGER = Logger.getLogger(PlayerQueue.class);
    private static final AtomicInteger IDS = new AtomicInteger(0);
    private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory(r -> "player-queue-worker-" + r.hashCode()));
    private final Session session;
    private final Player.Configuration conf;
    private final Listener listener;
    private final Map<Integer, QueueEntry> entries = new HashMap<>(5);
    private final AudioSink sink;
    private int currentEntryId = -1;
    private int nextEntryId = -1;

    public PlayerQueue(@NotNull Session session, @NotNull Player.Configuration conf, @NotNull Listener listener) {
        this.session = session;
        this.conf = conf;
        this.listener = listener;
        this.sink = new AudioSink(conf, listener);
    }

    /**
     * Resume the sink.
     */
    public void resume() {
        sink.resume();
    }

    /**
     * Pause the sink
     */
    public void pause(boolean release) {
        if (sink.pause(release))
            LOGGER.info("Sink released line.");
    }

    /**
     * Set the volume for the sink.
     *
     * @param volume The volume value from 0 to {@link Player#VOLUME_MAX}, inclusive.
     */
    public void setVolume(int volume) {
        sink.setVolume(volume);
    }

    /**
     * Clear the queue, the outputs and close all entries.
     */
    public void clear() {
        currentEntryId = -1;
        nextEntryId = -1;
        entries.values().removeIf(entry -> {
            entry.close();
            return true;
        });

        sink.clearOutputs();
    }

    /**
     * Create an entry for the specified content and start loading it asynchronously.
     *
     * @param playable The content this entry will play.
     * @return The entry ID
     */
    public int load(@NotNull PlayableId playable, @NotNull Map<String, String> metadata, int pos) {
        int id = IDS.getAndIncrement();
        QueueEntry entry = new QueueEntry(id, playable, metadata, this);
        executorService.execute(entry);
        executorService.execute(() -> {
            try {
                entry.load(session, conf, sink.getFormat(), pos);
                LOGGER.debug(String.format("Preloaded entry. {id: %d}", id));
            } catch (IOException | Codec.CodecException | MercuryClient.MercuryException | CdnManager.CdnException ex) {
                LOGGER.error(String.format("Failed preloading entry. {id: %d}", id), ex);
                listener.loadingError(id, playable, ex);
            } catch (ContentRestrictedException ex) {
                LOGGER.warn(String.format("Preloaded entry is content restricted. {id: %d}", id));
                entry.setContentRestricted(ex);
            }
        });

        entries.put(id, entry);
        LOGGER.debug(String.format("Created new entry. {id: %d, content: %s}", id, playable));
        return id;
    }

    /**
     * Seek the specified entry to the specified position.
     *
     * @param id  The entry ID
     * @param pos The time in milliseconds
     */
    public void seek(int id, int pos) {
        QueueEntry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException();

        executorService.execute(() -> {
            sink.flush();
            entry.seek(pos);
            listener.finishedSeek(id, pos);
        });
    }

    public void seekCurrent(int pos) {
        seek(currentEntryId, pos);
    }

    /**
     * Specifies what's going to play next, will start immediately if there's no current entry.
     *
     * @param id The ID of the next entry
     */
    public void follows(int id) {
        if (!entries.containsKey(id))
            throw new IllegalArgumentException();

        if (currentEntryId == -1) {
            currentEntryId = id;
            nextEntryId = -1;
            start(id);
        } else {
            nextEntryId = id;
        }
    }

    /**
     * Return metadata for the specified entry.
     *
     * @param id The entry ID
     * @return The metadata for the track or episode
     */
    @Nullable
    public TrackOrEpisode metadata(int id) {
        QueueEntry entry = entries.get(id);
        if (entry == null) return null;
        else return entry.metadata();
    }

    @Nullable
    public TrackOrEpisode currentMetadata() {
        return metadata(currentEntryId);
    }

    /**
     * Return the current position for the specified entry.
     *
     * @param id The entry ID
     * @return The current position of the player or {@code -1} if not ready.
     * @throws Codec.CannotGetTimeException If the time is unavailable for the codec being used.
     */
    public int time(int id) throws Codec.CannotGetTimeException {
        QueueEntry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException();

        return entry.getTime();
    }

    public int currentTime() throws Codec.CannotGetTimeException {
        if (currentEntryId == -1) return -1;
        else return time(currentEntryId);
    }

    @NotNull
    public PlayerMetrics currentMetrics() {
        QueueEntry entry = entries.get(currentEntryId);
        if (entry == null) throw new IllegalStateException();

        return entry.metrics();
    }

    /**
     * @param playable The {@link PlayableId}
     * @return Whether the given playable is the current entry.
     */
    public boolean isCurrent(@NotNull PlayableId playable) {
        QueueEntry entry = entries.get(currentEntryId);
        if (entry == null) return false;
        else return playable.equals(entry.playable);
    }

    /**
     * @param id The entry ID
     * @return Whether the given ID is the current entry ID.
     */
    public boolean isCurrent(int id) {
        return id == currentEntryId;
    }

    /**
     * Close the queue by closing all entries and the sink.
     */
    @Override
    public void close() {
        clear();
        sink.close();
    }

    private void start(int id) {
        QueueEntry entry = entries.get(id);
        if (entry == null) throw new IllegalStateException();
        if (entry.hasOutput()) {
            entry.toggleOutput(true);
            return;
        }

        MixingLine.MixingOutput out = sink.someOutput();
        if (out == null) throw new IllegalStateException();

        try {
            entry.load(session, conf, sink.getFormat(), -1);
        } catch (CdnManager.CdnException | IOException | MercuryClient.MercuryException | Codec.CodecException | ContentRestrictedException ex) {
            listener.loadingError(id, entry.playable, ex);
        }

        entry.setOutput(out);
        entry.toggleOutput(true);
    }

    @Override
    public void playbackException(int id, @NotNull Exception ex) {
        listener.playbackError(id, ex);
    }

    @Override
    public void playbackEnded(int id) {
        if (id == currentEntryId) {
            QueueEntry old = entries.remove(currentEntryId);
            if (old != null) old.close();

            if (nextEntryId != -1) {
                currentEntryId = nextEntryId;
                nextEntryId = -1;
                start(currentEntryId);
                listener.startedNextTrack(id, currentEntryId);
            } else {
                listener.endOfPlayback(id);
            }
        }
    }

    @Override
    public void playbackHalted(int id, int chunk) {
        listener.playbackHalted(id, chunk);
    }

    @Override
    public void playbackResumed(int id, int chunk, int duration) {
        listener.playbackResumedFromHalt(id, chunk, duration);
    }

    @Override
    public void instantReached(int entryId, int callbackId, int exact) {
        switch (callbackId) {
            case INSTANT_PRELOAD:
                if (entryId == currentEntryId)
                    executorService.execute(() -> listener.preloadNextTrack(entryId));
                break;
            case INSTANT_START_NEXT:
                if (entryId == currentEntryId && nextEntryId != -1)
                    start(nextEntryId);
                break;
            case INSTANT_END_NOW:
                QueueEntry entry = entries.remove(entryId);
                if (entry != null) entry.close();
                break;
        }
    }

    @Override
    public void startedLoading(int id) {
        listener.startedLoading(id);
    }

    @Override
    public void finishedLoading(int id) {
        listener.finishedLoading(id);

        QueueEntry entry = entries.get(id);
        if (conf.preloadEnabled() || entry.crossfadeController.fadeInEnabled())
            entry.notifyInstantFromEnd(INSTANT_PRELOAD, (int) TimeUnit.SECONDS.toMillis(20) + entry.crossfadeController.fadeOutStartTimeFromEnd());
    }

    public interface Listener extends AudioSink.Listener {
        /**
         * The track started loading.
         *
         * @param id The entry ID
         */
        void startedLoading(int id);

        /**
         * The track finished loading.
         *
         * @param id The entry ID
         */
        void finishedLoading(int id);

        /**
         * The track failed loading.
         *
         * @param id    The entry ID
         * @param track The content playable
         * @param ex    The exception thrown
         */
        void loadingError(int id, @NotNull PlayableId track, @NotNull Exception ex);

        /**
         * The playback of the current entry finished if no following track was specified.
         *
         * @param id The entry ID
         */
        void endOfPlayback(int id);

        /**
         * The playback of the current entry finished the next track already started played and is now {@link PlayerQueue#currentEntryId}.
         *
         * @param id   The entry ID
         * @param next The ID of the next entry
         */
        void startedNextTrack(int id, int next);

        /**
         * Instruct the player that it should start preloading the next track.
         *
         * @param id The entry ID of the currently playing track
         */
        void preloadNextTrack(int id);

        /**
         * An error occurred during playback.
         *
         * @param id The entry ID
         * @param ex The exception thrown
         */
        void playbackError(int id, @NotNull Exception ex);

        /**
         * The playback halted while trying to receive a chunk.
         *
         * @param id    The entry ID
         * @param chunk The chunk that is being retrieved
         */
        void playbackHalted(int id, int chunk);

        /**
         * The playback resumed from halt.
         *
         * @param id    The entry ID
         * @param chunk The chunk that was being retrieved
         * @param diff  The time taken to retrieve the chunk
         */
        void playbackResumedFromHalt(int id, int chunk, long diff);

        /**
         * The entry finished seeking.
         *
         * @param id  The entry ID
         * @param pos The seeked position
         */
        void finishedSeek(int id, int pos);
    }

    public static class PlayerMetrics {
        public int decodedLength = 0;
        public int size = 0;
        public int bitrate = 0;
        public int duration = 0;
        public String encoding = null;

        PlayerMetrics(@Nullable Codec codec) {
            if (codec == null) return;

            size = codec.size();
            duration = codec.duration();
            decodedLength = codec.decodedLength();

            AudioFormat format = codec.getAudioFormat();
            bitrate = (int) (format.getSampleRate() * format.getSampleSizeInBits());

            if (codec instanceof VorbisCodec) encoding = "vorbis";
            else if (codec instanceof Mp3Codec) encoding = "mp3";
        }
    }
}
