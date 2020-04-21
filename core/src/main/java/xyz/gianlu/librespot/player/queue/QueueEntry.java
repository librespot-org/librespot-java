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

/**
 * @author devgianlu
 */
class QueueEntry implements Closeable, Runnable, @Nullable HaltListener {
    private static final Logger LOGGER = Logger.getLogger(QueueEntry.class);
    final PlayableId playable;
    private final int id;
    private final Listener listener;
    private final Object playbackLock = new Object();
    private Codec codec;
    private TrackOrEpisode metadata;
    private volatile boolean closed = false;
    private volatile MixingLine.MixingOutput output;
    private long playbackHaltedAt = 0;
    private int notifyInstant = -1;
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
    void notifyInstant(int when) {
        if (codec != null) {
            try {
                int time = codec.time();
                if (time >= when) listener.instantReached(id, time);
            } catch (Codec.CannotGetTimeException ex) {
                return;
            }
        }

        notifyInstant = when;
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

            if (canGetTime && notifyInstant != -1) {
                try {
                    int time = codec.time();
                    if (time >= notifyInstant) {
                        notifyInstant = -1;
                        listener.instantReached(id, time);
                    }
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
        void playbackException(int id, @NotNull Exception ex);

        void playbackEnded(int id);

        void playbackHalted(int id, int chunk);

        void playbackResumed(int id, int chunk, int duration);

        void instantReached(int id, int exact);

        void startedLoading(int id);

        void finishedLoading(int id);
    }
}
