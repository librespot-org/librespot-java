/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.player.playback;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.audio.HaltListener;
import xyz.gianlu.librespot.audio.MetadataWrapper;
import xyz.gianlu.librespot.audio.PlayableContentFeeder;
import xyz.gianlu.librespot.audio.cdn.CdnManager;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.metadata.LocalId;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.player.PlayerConfiguration;
import xyz.gianlu.librespot.player.StateWrapper;
import xyz.gianlu.librespot.player.crossfade.CrossfadeController;
import xyz.gianlu.librespot.player.decoders.Decoder;
import xyz.gianlu.librespot.player.decoders.Decoders;
import xyz.gianlu.librespot.player.decoders.VorbisOnlyAudioQuality;
import xyz.gianlu.librespot.player.metrics.PlaybackMetrics;
import xyz.gianlu.librespot.player.metrics.PlayerMetrics;
import xyz.gianlu.librespot.player.mixing.AudioSink;
import xyz.gianlu.librespot.player.mixing.MixingLine;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * An object representing one single content/track/episode associated with its playback ID. This is responsible for IO operations,
 * decoding, metrics, crossfade and instant notifications.
 *
 * @author devgianlu
 */
class PlayerQueueEntry extends PlayerQueue.Entry implements Closeable, Runnable, HaltListener {
    static final int INSTANT_PRELOAD = 1;
    static final int INSTANT_START_NEXT = 2;
    static final int INSTANT_END = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerQueueEntry.class);
    final PlayableId playable;
    final String playbackId;
    private final PlayerConfiguration conf;
    private final boolean preloaded;
    private final Listener listener;
    private final Object playbackLock = new Object();
    private final TreeMap<Integer, Integer> notifyInstants = new TreeMap<>(Comparator.comparingInt(o -> o));
    private final AudioSink sink;
    private final Session session;
    CrossfadeController crossfade;
    PlaybackMetrics.Reason endReason = PlaybackMetrics.Reason.END_PLAY;
    private Decoder decoder;
    private MetadataWrapper metadata;
    private volatile boolean closed = false;
    private volatile MixingLine.MixingOutput output;
    private long playbackHaltedAt = 0;
    private volatile int seekTime = -1;
    private boolean retried = false;
    private PlayableContentFeeder.Metrics contentMetrics;

    PlayerQueueEntry(@NotNull AudioSink sink, @NotNull Session session, @NotNull PlayerConfiguration conf, @NotNull PlayableId playable, boolean preloaded, @NotNull Listener listener) {
        this.sink = sink;
        this.session = session;
        this.playbackId = StateWrapper.generatePlaybackId(session.random());
        this.conf = conf;
        this.playable = playable;
        this.preloaded = preloaded;
        this.listener = listener;

        LOGGER.trace("Created new {}.", this);
    }

    @NotNull
    PlayerQueueEntry retrySelf(boolean preloaded) {
        if (retried) throw new IllegalStateException();

        PlayerQueueEntry retry = new PlayerQueueEntry(sink, session, conf, playable, preloaded, listener);
        retry.retried = true;
        return retry;
    }

    /**
     * Loads the content described by this entry.
     *
     * @throws PlayableContentFeeder.ContentRestrictedException If the content cannot be retrieved because of restrictions (this condition won't change with a retry).
     */
    private void load(boolean preload) throws IOException, Decoder.CodecException, MercuryClient.MercuryException, CdnManager.CdnException, PlayableContentFeeder.ContentRestrictedException {
        PlayableContentFeeder.LoadedStream stream;
        if (playable instanceof LocalId)
            stream = PlayableContentFeeder.LoadedStream.forLocalFile((LocalId) playable,
                    new File(conf.localFilesPath, ((LocalId) playable).name()));
        else
            stream = session.contentFeeder().load(playable, new VorbisOnlyAudioQuality(conf.preferredQuality), preload, this);

        metadata = stream.metadata;
        contentMetrics = stream.metrics;

        if (metadata.isEpisode() && metadata.episode != null) {
            LOGGER.info("Loaded episode. {name: '{}', duration: {}, uri: {}, id: {}}", metadata.episode.getName(),
                    metadata.episode.getDuration(), playable.toSpotifyUri(), playbackId);
        } else if (metadata.isTrack() && metadata.track != null) {
            LOGGER.info("Loaded track. {name: '{}', artists: '{}', duration: {}, uri: {}, id: {}}", metadata.track.getName(),
                    Utils.artistsToString(metadata.track.getArtistList()), metadata.track.getDuration(), playable.toSpotifyUri(), playbackId);
        } else if (playable instanceof LocalId) {
            LOGGER.info("Loaded local file. {filename: '{}', duration: {}, uri: {}, id: {}}", ((LocalId) playable).name(),
                    ((LocalId) playable).duration(), playable.toSpotifyUri(), playbackId);
        }

        crossfade = new CrossfadeController(playbackId, metadata.duration(), listener.metadataFor(playable).orElse(Collections.emptyMap()), conf);
        if (crossfade.hasAnyFadeOut() || conf.preloadEnabled)
            notifyInstant(INSTANT_PRELOAD, (int) (crossfade.fadeOutStartTimeMin() - TimeUnit.SECONDS.toMillis(20)));

        float normalizationFactor;
        if (stream.normalizationData == null || !conf.enableNormalisation) normalizationFactor = 1;
        else normalizationFactor = stream.normalizationData.getFactor(conf.normalisationPregain);

        decoder = Decoders.initDecoder(stream.in.codec(), stream.in, normalizationFactor, metadata.duration());
        if (decoder == null)
            throw new UnsupportedEncodingException(stream.in.codec().toString());

        LOGGER.trace("Loaded {} codec. {of: {}, format: {}, playbackId: {}}", stream.in.codec(), stream.in.describe(), decoder.getAudioFormat(), playbackId);
    }

    /**
     * Gets the metadata associated with this entry.
     *
     * @return A {@link MetadataWrapper} object or {@code null} if not loaded yet
     */
    @Nullable
    public MetadataWrapper metadata() {
        return metadata;
    }

    /**
     * Returns the metrics for this entry.
     *
     * @return A {@link PlayerMetrics} object
     */
    @NotNull
    PlayerMetrics metrics() {
        return new PlayerMetrics(contentMetrics, crossfade, decoder);
    }

    /**
     * Returns the current position.
     *
     * @return The current position of the player or {@code -1} if not ready.
     * @throws Decoder.CannotGetTimeException If the time is unavailable for the codec being used.
     */
    int getTime() throws Decoder.CannotGetTimeException {
        return decoder == null ? -1 : decoder.time();
    }

    /**
     * Returns the current position.
     *
     * @return The current position of the player or {@code -1} if not available.
     * @see PlayerQueueEntry#getTime()
     */
    int getTimeNoThrow() {
        try {
            return getTime();
        } catch (Decoder.CannotGetTimeException e) {
            return -1;
        }
    }

    /**
     * Seeks to the specified position.
     *
     * @param pos The time in milliseconds
     */
    void seek(int pos) {
        seekTime = pos;
        if (output != null) output.emptyBuffer();
    }

    /**
     * Sets the output to {@param output}. As soon as this method returns the entry will start playing.
     *
     * @throws IllegalStateException If the output is already set. Will also clear {@param output}.
     */
    void setOutput(@NotNull MixingLine.MixingOutput output) {
        if (closed || hasOutput()) {
            output.clear();
            throw new IllegalStateException("Cannot set output for " + this);
        }

        synchronized (playbackLock) {
            this.output = output;
            playbackLock.notifyAll();
        }
    }

    /**
     * Removes the output. As soon as this method is called the entry will stop playing.
     */
    private void clearOutput() {
        if (output != null) {
            MixingLine.MixingOutput tmp = output;
            output = null;

            tmp.toggle(false, null);
            tmp.clear();

            LOGGER.debug("{} has been removed from output.", this);
        }

        synchronized (playbackLock) {
            playbackLock.notifyAll();
        }
    }

    /**
     * @return Whether the entry is associated with an output.
     */
    public boolean hasOutput() {
        return output != null;
    }

    /**
     * Instructs to notify when this time instant is reached.
     *
     * @param callbackId The callback ID
     * @param when       The time in milliseconds
     */
    void notifyInstant(int callbackId, int when) {
        if (decoder != null) {
            try {
                int time = decoder.time();
                if (time >= when) {
                    listener.instantReached(this, callbackId, time);
                    return;
                }
            } catch (Decoder.CannotGetTimeException ex) {
                return;
            }
        }

        notifyInstants.put(when, callbackId);
    }

    @Override
    public void run() {
        listener.startedLoading(this);

        try {
            load(preloaded);
        } catch (IOException | PlayableContentFeeder.ContentRestrictedException | CdnManager.CdnException | MercuryClient.MercuryException | Decoder.CodecException ex) {
            close();
            listener.loadingError(this, ex, retried);
            LOGGER.trace("{} terminated at loading.", this, ex);
            return;
        }

        if (seekTime != -1) {
            decoder.seek(seekTime);
            seekTime = -1;
        }

        listener.finishedLoading(this, metadata);

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

                if (output == null) continue;
            }

            if (closed) break;
            output.toggle(true, decoder.getAudioFormat());

            if (seekTime != -1) {
                decoder.seek(seekTime);
                seekTime = -1;
            }

            if (canGetTime) {
                try {
                    int time = decoder.time();
                    if (!notifyInstants.isEmpty()) checkInstants(time);
                    if (output == null)
                        continue;

                    output.gain(crossfade.getGain(time));
                } catch (Decoder.CannotGetTimeException ex) {
                    canGetTime = false;
                }
            }

            try {
                if (decoder.writeSomeTo(output) == -1) {
                    try {
                        int time = decoder.time();
                        LOGGER.debug("Player time offset is {}. {id: {}}", metadata.duration() - time, playbackId);
                    } catch (Decoder.CannotGetTimeException ignored) {
                    }

                    close();
                    break;
                }
            } catch (IOException | Decoder.CodecException ex) {
                if (!closed) {
                    close();
                    listener.playbackError(this, ex);
                    return;
                }

                break;
            }
        }

        if (output != null) output.toggle(false, null);
        listener.playbackEnded(this);
        LOGGER.trace("{} terminated.", this);
    }

    private void checkInstants(int time) {
        int key = notifyInstants.firstKey();
        if (time >= key) {
            int callbackId = notifyInstants.remove(key);
            listener.instantReached(this, callbackId, time);
            if (!notifyInstants.isEmpty()) checkInstants(time);
        }
    }

    /**
     * Close this entry if it's not attached to an output.
     *
     * @return Whether it has been closed
     */
    boolean closeIfUseless() {
        if (!hasOutput()) {
            close();
            return true;
        }

        return false;
    }

    @Override
    public void close() {
        closed = true;
        clearOutput();

        try {
            if (decoder != null) decoder.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void streamReadHalted(int chunk, long time) {
        playbackHaltedAt = time;
        listener.playbackHalted(this, chunk);
    }

    @Override
    public void streamReadResumed(int chunk, long time) {
        if (playbackHaltedAt == 0) return;

        int duration = (int) (time - playbackHaltedAt);
        listener.playbackResumed(this, chunk, duration);
    }

    @Override
    public String toString() {
        return "PlayerQueueEntry{" + playbackId + "}";
    }

    interface Listener {
        /**
         * An error occurred during playback.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         * @param ex    The exception thrown
         */
        void playbackError(@NotNull PlayerQueueEntry entry, @NotNull Exception ex);

        /**
         * The playback of the current entry ended.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         */
        void playbackEnded(@NotNull PlayerQueueEntry entry);

        /**
         * The playback halted while trying to receive a chunk.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         * @param chunk The chunk that is being retrieved
         */
        void playbackHalted(@NotNull PlayerQueueEntry entry, int chunk);

        /**
         * The playback resumed from halt.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         * @param chunk The chunk that was being retrieved
         * @param diff  The time taken to retrieve the chunk
         */
        void playbackResumed(@NotNull PlayerQueueEntry entry, int chunk, int diff);

        /**
         * Notify that a previously request instant has been reached. This is called from the runner, be careful.
         *
         * @param entry      The {@link PlayerQueueEntry} that called this
         * @param callbackId The callback ID for the instant
         * @param exactTime  The exact time the instant was reached
         */
        void instantReached(@NotNull PlayerQueueEntry entry, int callbackId, int exactTime);

        /**
         * The track started loading.
         *
         * @param entry The {@link PlayerQueueEntry} that called this
         */
        void startedLoading(@NotNull PlayerQueueEntry entry);

        /**
         * The track failed loading.
         *
         * @param entry   The {@link PlayerQueueEntry} that called this
         * @param ex      The exception thrown
         * @param retried Whether this is the second time an error occurs
         */
        void loadingError(@NotNull PlayerQueueEntry entry, @NotNull Exception ex, boolean retried);

        /**
         * The track finished loading.
         *
         * @param entry    The {@link PlayerQueueEntry} that called this
         * @param metadata The {@link MetadataWrapper} object
         */
        void finishedLoading(@NotNull PlayerQueueEntry entry, @NotNull MetadataWrapper metadata);

        /**
         * Get the metadata for this content.
         *
         * @param playable The content
         * @return A map containing all the metadata related
         */
        @NotNull
        Optional<Map<String, String>> metadataFor(@NotNull PlayableId playable);
    }
}
