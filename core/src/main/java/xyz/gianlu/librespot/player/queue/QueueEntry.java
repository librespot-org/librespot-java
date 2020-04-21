package xyz.gianlu.librespot.player.queue;

import javazoom.jl.decoder.BitstreamException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.ContentRestrictedException;
import xyz.gianlu.librespot.player.HaltListener;
import xyz.gianlu.librespot.player.Player;
import xyz.gianlu.librespot.player.TrackOrEpisode;
import xyz.gianlu.librespot.player.codecs.Codec;
import xyz.gianlu.librespot.player.codecs.Mp3Codec;
import xyz.gianlu.librespot.player.codecs.VorbisCodec;
import xyz.gianlu.librespot.player.codecs.VorbisOnlyAudioQuality;
import xyz.gianlu.librespot.player.feeders.PlayableContentFeeder;
import xyz.gianlu.librespot.player.feeders.cdn.CdnManager;
import xyz.gianlu.librespot.player.mixing.MixingLine;

import javax.sound.sampled.AudioFormat;
import java.io.Closeable;
import java.io.IOException;
import java.util.Comparator;
import java.util.TreeMap;

/**
 * @author devgianlu
 */
class QueueEntry implements Closeable, Runnable, @Nullable HaltListener {
    private static final Logger LOGGER = Logger.getLogger(QueueEntry.class);
    final PlayableId playable;
    private final int id;
    private final Listener listener;
    private final Object playbackLock = new Object();
    private final TreeMap<Integer, Integer> notifyInstants = new TreeMap<>(Comparator.comparingInt(o -> o));
    private Codec codec;
    private TrackOrEpisode metadata;
    private volatile boolean closed = false;
    private volatile MixingLine.MixingOutput output;
    private long playbackHaltedAt = 0;
    private ContentRestrictedException contentRestricted = null;

    QueueEntry(int id, @NotNull PlayableId playable, @NotNull Listener listener) {
        this.id = id;
        this.playable = playable;
        this.listener = listener;
    }

    @Nullable
    public TrackOrEpisode metadata() {
        return metadata;
    }

    synchronized void load(@NotNull Session session, @NotNull Player.Configuration conf, @NotNull AudioFormat format) throws IOException, Codec.CodecException, MercuryClient.MercuryException, CdnManager.CdnException, ContentRestrictedException {
        if (contentRestricted != null) throw contentRestricted;
        if (codec != null) {
            notifyAll();
            return;
        }

        listener.startedLoading(id);

        PlayableContentFeeder.LoadedStream stream = session.contentFeeder().load(playable, new VorbisOnlyAudioQuality(conf.preferredQuality()), this);
        metadata = new TrackOrEpisode(stream.track, stream.episode);

        if (playable instanceof EpisodeId && stream.episode != null) {
            LOGGER.info(String.format("Loaded episode. {name: '%s', uri: %s, id: %d}", stream.episode.getName(), playable.toSpotifyUri(), id));
        } else if (playable instanceof TrackId && stream.track != null) {
            LOGGER.info(String.format("Loaded track. {name: '%s', artists: '%s', uri: %s, id: %d}", stream.track.getName(),
                    Utils.artistsToString(stream.track.getArtistList()), playable.toSpotifyUri(), id));
        }

        switch (stream.in.codec()) {
            case VORBIS:
                codec = new VorbisCodec(format, stream.in, stream.normalizationData, conf, metadata.duration());
                break;
            case MP3:
                try {
                    codec = new Mp3Codec(format, stream.in, stream.normalizationData, conf, metadata.duration());
                } catch (BitstreamException ex) {
                    throw new IOException(ex);
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown codec: " + stream.in.codec());
        }

        contentRestricted = null;
        LOGGER.trace(String.format("Loaded %s codec. {fileId: %s, format: %s, id: %d}", stream.in.codec(), stream.in.describe(), codec.getAudioFormat(), id));
        notifyAll();

        listener.finishedLoading(id);
    }

    private synchronized void waitReady() throws InterruptedException {
        if (codec != null) return;
        wait();
    }

    void setContentRestricted(@NotNull ContentRestrictedException ex) {
        contentRestricted = ex;
    }

    /**
     * Returns the metrics for this entry.
     *
     * @return A {@link xyz.gianlu.librespot.player.queue.PlayerQueue.PlayerMetrics} object
     */
    @NotNull
    PlayerQueue.PlayerMetrics metrics() {
        return new PlayerQueue.PlayerMetrics(codec);
    }

    /**
     * Return the current position.
     *
     * @return The current position of the player or {@code -1} if not ready.
     * @throws Codec.CannotGetTimeException If the time is unavailable for the codec being used.
     */
    int getTime() throws Codec.CannotGetTimeException {
        return codec == null ? -1 : codec.time();
    }

    /**
     * Seek to the specified position.
     *
     * @param pos The time in milliseconds
     */
    void seek(int pos) {
        try {
            waitReady();
        } catch (InterruptedException ex) {
            return;
        }

        output.stream().emptyBuffer();
        codec.seek(pos);
    }

    /**
     * Set the output.
     */
    void setOutput(@NotNull MixingLine.MixingOutput output) {
        synchronized (playbackLock) {
            this.output = output;
            playbackLock.notifyAll();
        }

        this.output.toggle(true);
    }

    /**
     * Remove the output.
     */
    void clearOutput() {
        if (output != null) {
            output.toggle(false);
            output.clear();
        }

        synchronized (playbackLock) {
            output = null;
            playbackLock.notifyAll();
        }
    }

    /**
     * Instructs to notify when this time instant is reached.
     *
     * @param when The time in milliseconds
     */
    void notifyInstant(int callbackId, int when) {
        if (codec != null) {
            try {
                int time = codec.time();
                if (time >= when) {
                    listener.instantReached(id, callbackId, time);
                    return;
                }
            } catch (Codec.CannotGetTimeException ex) {
                return;
            }
        }

        notifyInstants.put(when, callbackId);
    }

    @Override
    public void run() {
        if (codec == null) {
            try {
                waitReady();
            } catch (InterruptedException ex) {
                return;
            }
        }

        boolean canGetTime = true;
        while (!closed) {
            if (output == null) {
                synchronized (playbackLock) {
                    try {
                        playbackLock.wait();
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }

            if (canGetTime && !notifyInstants.isEmpty()) {
                try {
                    int time = codec.time();
                    checkInstants(time);
                } catch (Codec.CannotGetTimeException ex) {
                    canGetTime = false;
                }
            }

            try {
                if (codec.writeSomeTo(output.stream()) == -1) {
                    listener.playbackEnded(id);
                    break;
                }
            } catch (IOException | Codec.CodecException ex) {
                if (closed) break;
                listener.playbackException(id, ex);
            }
        }
    }

    private void checkInstants(int time) {
        int key = notifyInstants.firstKey();
        if (time >= key) {
            int callbackId = notifyInstants.remove(key);
            listener.instantReached(id, callbackId, time);
            if (!notifyInstants.isEmpty()) checkInstants(time);
        }
    }

    @Override
    public void close() {
        closed = true;
        clearOutput();
    }

    @Override
    public void streamReadHalted(int chunk, long time) {
        playbackHaltedAt = time;
        listener.playbackHalted(id, chunk);
    }

    @Override
    public void streamReadResumed(int chunk, long time) {
        if (playbackHaltedAt == 0) return;

        int duration = (int) (time - playbackHaltedAt);
        listener.playbackResumed(id, chunk, duration);
    }

    interface Listener {
        /**
         * An error occurred during playback.
         *
         * @param id The entry ID
         * @param ex The exception thrown
         */
        void playbackException(int id, @NotNull Exception ex);

        /**
         * The playback of the current entry ended.
         *
         * @param id The entry ID
         */
        void playbackEnded(int id);

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
        void playbackResumed(int id, int chunk, int diff);

        /**
         * Notify that a previously request instant has been reached. This is called from the runner, be careful.
         *
         * @param entryId    The entry ID
         * @param callbackId The callback ID for the instant
         * @param exactTime  The exact time the instant was reached
         */
        void instantReached(int entryId, int callbackId, int exactTime);

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
    }
}
