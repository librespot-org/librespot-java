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

package xyz.gianlu.librespot.audio;

import com.google.protobuf.ByteString;
import com.spotify.metadata.Metadata;
import com.spotify.storage.StorageResolve.StorageResolveResponse;
import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.gianlu.librespot.audio.cdn.CdnFeedHelper;
import xyz.gianlu.librespot.audio.cdn.CdnManager;
import xyz.gianlu.librespot.audio.format.AudioQualityPicker;
import xyz.gianlu.librespot.audio.format.SuperAudioFormat;
import xyz.gianlu.librespot.audio.storage.AudioFileFetch;
import xyz.gianlu.librespot.audio.storage.StorageFeedHelper;
import xyz.gianlu.librespot.common.NameThreadFactory;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.metadata.EpisodeId;
import xyz.gianlu.librespot.metadata.LocalId;
import xyz.gianlu.librespot.metadata.PlayableId;
import xyz.gianlu.librespot.metadata.TrackId;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static xyz.gianlu.librespot.audio.storage.ChannelManager.CHUNK_SIZE;

/**
 * @author Gianlu
 */
public final class PlayableContentFeeder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayableContentFeeder.class);
    private static final String STORAGE_RESOLVE_INTERACTIVE = "/storage-resolve/files/audio/interactive/%s";
    private static final String STORAGE_RESOLVE_INTERACTIVE_PREFETCH = "/storage-resolve/files/audio/interactive_prefetch/%s";
    protected final Session session;

    public PlayableContentFeeder(@NotNull Session session) {
        this.session = session;
    }

    @Nullable
    private static Metadata.Track pickAlternativeIfNecessary(@NotNull Metadata.Track track) {
        if (track.getFileCount() > 0) return track;

        for (Metadata.Track alt : track.getAlternativeList()) {
            if (alt.getFileCount() > 0) {
                Metadata.Track.Builder builder = track.toBuilder();
                builder.clearFile();
                builder.addAllFile(alt.getFileList());
                return builder.build();
            }
        }

        return null;
    }

    @NotNull
    public final LoadedStream load(@NotNull PlayableId id, @NotNull AudioQualityPicker audioQualityPicker, boolean preload, @Nullable HaltListener haltListener) throws CdnManager.CdnException, ContentRestrictedException, MercuryClient.MercuryException, IOException {
        if (id instanceof TrackId)
            return loadTrack((TrackId) id, audioQualityPicker, preload, haltListener);
        else if (id instanceof EpisodeId)
            return loadEpisode((EpisodeId) id, audioQualityPicker, preload, haltListener);
        else
            throw new IllegalArgumentException("Unknown content: " + id);
    }

    @NotNull
    private StorageResolveResponse resolveStorageInteractive(@NotNull ByteString fileId, boolean preload) throws IOException, MercuryClient.MercuryException {
        try (Response resp = session.api().send("GET", String.format(preload ? STORAGE_RESOLVE_INTERACTIVE_PREFETCH : STORAGE_RESOLVE_INTERACTIVE, Utils.bytesToHex(fileId)), null, null)) {
            if (resp.code() != 200) throw new IOException(resp.code() + ": " + resp.message());

            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Response body is empty!");

            return StorageResolveResponse.parseFrom(body.byteStream());
        }
    }

    private @NotNull LoadedStream loadTrack(@NotNull TrackId id, @NotNull AudioQualityPicker audioQualityPicker, boolean preload, @Nullable HaltListener haltListener) throws IOException, MercuryClient.MercuryException, ContentRestrictedException, CdnManager.CdnException {
        Metadata.Track original = session.api().getMetadata4Track(id);
        Metadata.Track track = pickAlternativeIfNecessary(original);
        if (track == null) {
            String country = session.countryCode();
            if (country != null) ContentRestrictedException.checkRestrictions(country, original.getRestrictionList());

            LOGGER.error("Couldn't find playable track: " + id.toSpotifyUri());
            throw new FeederException();
        }

        return loadTrack(track, audioQualityPicker, preload, haltListener);
    }

    @NotNull
    @Contract("_, null, null, _, _, _ -> fail")
    private LoadedStream loadCdnStream(@NotNull Metadata.AudioFile file, @Nullable Metadata.Track track, @Nullable Metadata.Episode episode, @NotNull String urlStr, boolean preload, @Nullable HaltListener haltListener) throws IOException, CdnManager.CdnException {
        if (track == null && episode == null)
            throw new IllegalStateException();

        HttpUrl url = HttpUrl.get(urlStr);
        if (track != null) return CdnFeedHelper.loadTrack(session, track, file, url, preload, haltListener);
        else return CdnFeedHelper.loadEpisode(session, episode, file, url, haltListener);
    }

    @NotNull
    @Contract("_, null, null, _, _ -> fail")
    private LoadedStream loadStream(@NotNull Metadata.AudioFile file, @Nullable Metadata.Track track, @Nullable Metadata.Episode episode, boolean preload, @Nullable HaltListener haltListener) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException {
        if (track == null && episode == null)
            throw new IllegalStateException();

        StorageResolveResponse resp = resolveStorageInteractive(file.getFileId(), preload);
        switch (resp.getResult()) {
            case CDN:
                if (track != null) return CdnFeedHelper.loadTrack(session, track, file, resp, preload, haltListener);
                else return CdnFeedHelper.loadEpisode(session, episode, file, resp, haltListener);
            case STORAGE:
                try {
                    if (track != null) return StorageFeedHelper.loadTrack(session, track, file, preload, haltListener);
                    else return StorageFeedHelper.loadEpisode(session, episode, file, preload, haltListener);
                } catch (AudioFileFetch.StorageNotAvailable ex) {
                    LOGGER.info("Storage is not available. Going CDN: " + ex.cdnUrl);
                    return loadCdnStream(file, track, episode, ex.cdnUrl, preload, haltListener);
                }
            case RESTRICTED:
                throw new IllegalStateException("Content is restricted!");
            case UNRECOGNIZED:
                throw new IllegalStateException("Content is unrecognized!");
            default:
                throw new IllegalStateException("Unknown result: " + resp.getResult());
        }
    }

    @NotNull
    private LoadedStream loadTrack(@NotNull Metadata.Track track, @NotNull AudioQualityPicker audioQualityPicker, boolean preload, @Nullable HaltListener haltListener) throws IOException, CdnManager.CdnException, MercuryClient.MercuryException {
        Metadata.AudioFile file = audioQualityPicker.getFile(track.getFileList());
        if (file == null) {
            LOGGER.error("Couldn't find any suitable audio file, available: {}", Utils.formatsToString(track.getFileList()));
            throw new FeederException();
        }

        return loadStream(file, track, null, preload, haltListener);
    }

    @NotNull
    private LoadedStream loadEpisode(@NotNull EpisodeId id, @NotNull AudioQualityPicker audioQualityPicker, boolean preload, @Nullable HaltListener haltListener) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException {
        Metadata.Episode episode = session.api().getMetadata4Episode(id);

        if (episode.hasExternalUrl()) {
            return CdnFeedHelper.loadEpisodeExternal(session, episode, haltListener);
        } else {
            Metadata.AudioFile file = audioQualityPicker.getFile(episode.getAudioList());
            if (file == null) {
                LOGGER.error("Couldn't find any suitable audio file, available: {}", Utils.formatsToString(episode.getAudioList()));
                throw new FeederException();
            }

            return loadStream(file, null, episode, preload, haltListener);
        }
    }

    private static class FileAudioStream implements GeneralAudioStream {
        private static final Logger LOGGER = LoggerFactory.getLogger(FileAudioStream.class);
        private final File file;
        private final RandomAccessFile raf;
        private final byte[][] buffer;
        private final int chunks;
        private final int size;
        private final boolean[] available;
        private final boolean[] requested;
        private final ExecutorService executorService = Executors.newCachedThreadPool(new NameThreadFactory((r) -> "file-async-" + r.hashCode()));

        FileAudioStream(File file) throws IOException {
            this.file = file;
            this.raf = new RandomAccessFile(file, "r");

            this.size = (int) raf.length();
            this.chunks = (size + CHUNK_SIZE - 1) / CHUNK_SIZE;
            this.buffer = new byte[chunks][];
            this.available = new boolean[chunks];
            this.requested = new boolean[chunks];
        }

        @Override
        public @NotNull AbsChunkedInputStream stream() {
            return new AbsChunkedInputStream(false) {
                @Override
                protected byte[][] buffer() {
                    return buffer;
                }

                @Override
                public int size() {
                    return size;
                }

                @Override
                protected boolean[] requestedChunks() {
                    return requested;
                }

                @Override
                protected boolean[] availableChunks() {
                    return available;
                }

                @Override
                protected int chunks() {
                    return chunks;
                }

                @Override
                protected void requestChunkFromStream(int index) {
                    executorService.submit(() -> {
                        try {
                            raf.seek((long) index * CHUNK_SIZE);
                            raf.read(buffer[index]);
                            notifyChunkAvailable(index);
                        } catch (IOException ex) {
                            notifyChunkError(index, new ChunkException(ex));
                        }
                    });
                }

                @Override
                public void streamReadHalted(int chunk, long time) {
                    LOGGER.warn("Not dispatching stream read halted event {chunk: {}}", chunk);
                }

                @Override
                public void streamReadResumed(int chunk, long time) {
                    LOGGER.warn("Not dispatching stream read resumed event {chunk: {}}", chunk);
                }
            };
        }

        @Override
        public @NotNull SuperAudioFormat codec() {
            return SuperAudioFormat.MP3; // FIXME: Detect codec
        }

        @Override
        public @NotNull String describe() {
            return "{file: " + file.getAbsolutePath() + "}";
        }

        @Override
        public int decryptTimeMs() {
            return 0;
        }
    }

    public static class LoadedStream {
        public final MetadataWrapper metadata;
        public final GeneralAudioStream in;
        public final NormalizationData normalizationData;
        public final Metrics metrics;

        public LoadedStream(@NotNull Metadata.Track track, @NotNull GeneralAudioStream in, @Nullable NormalizationData normalizationData, @NotNull Metrics metrics) {
            this.metadata = new MetadataWrapper(track, null, null);
            this.in = in;
            this.normalizationData = normalizationData;
            this.metrics = metrics;
        }

        public LoadedStream(@NotNull Metadata.Episode episode, @NotNull GeneralAudioStream in, @Nullable NormalizationData normalizationData, @NotNull Metrics metrics) {
            this.metadata = new MetadataWrapper(null, episode, null);
            this.in = in;
            this.normalizationData = normalizationData;
            this.metrics = metrics;
        }

        private LoadedStream(@NotNull LocalId id, @NotNull GeneralAudioStream in) {
            this.metadata = new MetadataWrapper(null, null, id);
            this.in = in;
            this.normalizationData = null;
            this.metrics = new Metrics(null, false, 0);
        }

        @NotNull
        public static LoadedStream forLocalFile(@NotNull LocalId id, @NotNull File file) throws IOException {
            return new LoadedStream(id, new FileAudioStream(file));
        }
    }

    public static class Metrics {
        public final String fileId;
        public final boolean preloadedAudioKey;
        public final int audioKeyTime;

        public Metrics(@Nullable ByteString fileId, boolean preloadedAudioKey, int audioKeyTime) {
            this.fileId = fileId == null ? null : Utils.bytesToHex(fileId).toLowerCase();
            this.preloadedAudioKey = preloadedAudioKey;
            this.audioKeyTime = audioKeyTime;

            if (preloadedAudioKey && audioKeyTime != -1)
                throw new IllegalStateException();
        }
    }

    public static class FeederException extends IOException {
        FeederException() {
        }
    }

    public static class ContentRestrictedException extends Exception {

        public static void checkRestrictions(@NotNull String country, @NotNull List<Metadata.Restriction> restrictions) throws ContentRestrictedException {
            for (Metadata.Restriction restriction : restrictions)
                if (isRestricted(country, restriction))
                    throw new ContentRestrictedException();
        }

        private static boolean isInList(@NotNull String list, @NotNull String match) {
            for (int i = 0; i < list.length(); i += 2)
                if (list.substring(i, i + 2).equals(match))
                    return true;

            return false;
        }

        private static boolean isRestricted(@NotNull String countryCode, @NotNull Metadata.Restriction restriction) {
            if (restriction.hasCountriesAllowed()) {
                String allowed = restriction.getCountriesAllowed();
                if (allowed.isEmpty()) return true;

                if (!isInList(restriction.getCountriesForbidden(), countryCode))
                    return true;
            }

            if (restriction.hasCountriesForbidden())
                return isInList(restriction.getCountriesForbidden(), countryCode);

            return false;
        }
    }
}
