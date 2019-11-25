package xyz.gianlu.librespot.player.feeders;

import com.google.protobuf.ByteString;
import com.spotify.metadata.proto.Metadata;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.download.proto.StorageResolve.StorageResolveResponse;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.AbsChunckedInputStream;
import xyz.gianlu.librespot.player.ContentRestrictedException;
import xyz.gianlu.librespot.player.GeneralAudioStream;
import xyz.gianlu.librespot.player.NormalizationData;
import xyz.gianlu.librespot.player.codecs.AudioQuality;
import xyz.gianlu.librespot.player.codecs.AudioQualityPreference;
import xyz.gianlu.librespot.player.feeders.cdn.CdnFeedHelper;
import xyz.gianlu.librespot.player.feeders.cdn.CdnManager;
import xyz.gianlu.librespot.player.feeders.storage.StorageFeedHelper;

import java.io.IOException;

/**
 * @author Gianlu
 */
public final class PlayableContentFeeder {
    private static final Logger LOGGER = Logger.getLogger(PlayableContentFeeder.class);
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
    public final LoadedStream load(@NotNull PlayableId id, @NotNull AudioQualityPreference audioQualityPreference, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws CdnManager.CdnException, ContentRestrictedException, MercuryClient.MercuryException, IOException {
        if (id instanceof TrackId) return loadTrack((TrackId) id, audioQualityPreference, haltListener);
        else if (id instanceof EpisodeId) return loadEpisode((EpisodeId) id, audioQualityPreference, haltListener);
        else throw new IllegalArgumentException("Unknown PlayableId: " + id);
    }

    @NotNull
    private StorageResolveResponse resolveStorageInteractive(@NotNull ByteString fileId) throws IOException, MercuryClient.MercuryException {
        try (Response resp = session.api().send("GET", String.format("/storage-resolve/files/audio/interactive/%s", Utils.bytesToHex(fileId)), null, null)) {
            if (resp.code() != 200) throw new IOException(resp.code() + ": " + resp.message());

            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Response body is empty!");

            return StorageResolveResponse.parseFrom(body.byteStream());
        }
    }

    private @NotNull LoadedStream loadTrack(@NotNull TrackId id, @NotNull AudioQualityPreference audioQualityPreference, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, MercuryClient.MercuryException, ContentRestrictedException, CdnManager.CdnException {
        Metadata.Track original = session.api().getMetadata4Track(id);
        Metadata.Track track = pickAlternativeIfNecessary(original);
        if (track == null) {
            String country = session.countryCode();
            if (country != null) ContentRestrictedException.checkRestrictions(country, original.getRestrictionList());

            LOGGER.fatal("Couldn't find playable track: " + Utils.bytesToHex(id.getGid()));
            throw new FeederException();
        }

        return loadTrack(track, audioQualityPreference, haltListener);
    }

    @NotNull
    private LoadedStream loadStream(@NotNull Metadata.AudioFile file, @Nullable Metadata.Track track, @Nullable Metadata.Episode episode, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException {
        StorageResolveResponse resp = resolveStorageInteractive(file.getFileId());
        switch (resp.getResult()) {
            case CDN:
                if (track != null) return CdnFeedHelper.loadTrack(session, track, file, resp, haltListener);
                else if (episode != null) return CdnFeedHelper.loadEpisode(session, episode, file, resp, haltListener);
                else throw new IllegalStateException();
            case STORAGE:
                if (track != null) return StorageFeedHelper.loadTrack(session, track, file, haltListener);
                else if (episode != null) return StorageFeedHelper.loadEpisode(session, episode, file, haltListener);
                else throw new IllegalStateException();
            case RESTRICTED:
                throw new IllegalStateException("Content is restricted!");
            case UNRECOGNIZED:
                throw new IllegalStateException("Content is unrecognized!");
            default:
                throw new IllegalStateException("Unknown result: " + resp.getResult());
        }
    }

    @NotNull
    private LoadedStream loadTrack(@NotNull Metadata.Track track, @NotNull AudioQualityPreference audioQualityPreference, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, CdnManager.CdnException, MercuryClient.MercuryException {
        Metadata.AudioFile file = audioQualityPreference.getFile(track.getFileList());
        if (file == null) {
            LOGGER.fatal(String.format("Couldn't find any suitable audio file, available: %s", AudioQuality.listFormats(track.getFileList())));
            throw new FeederException();
        }

        return loadStream(file, track, null, haltListener);
    }

    @NotNull
    private LoadedStream loadEpisode(@NotNull EpisodeId id, @NotNull AudioQualityPreference audioQualityPreference, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException {
        Metadata.Episode episode = session.api().getMetadata4Episode(id);

        if (episode.hasExternalUrl()) {
            return CdnFeedHelper.loadEpisodeExternal(session, episode, haltListener);
        } else {
            Metadata.AudioFile file = audioQualityPreference.getFile(episode.getAudioList());
            if (file == null) {
                LOGGER.fatal(String.format("Couldn't find any suitable audio file, available: %s", AudioQuality.listFormats(episode.getAudioList())));
                throw new FeederException();
            }

            return loadStream(file, null, episode, haltListener);
        }
    }

    public static class LoadedStream {
        public final Metadata.Episode episode;
        public final Metadata.Track track;
        public final GeneralAudioStream in;
        public final NormalizationData normalizationData;

        public LoadedStream(@NotNull Metadata.Track track, @NotNull GeneralAudioStream in, @Nullable NormalizationData normalizationData) {
            this.track = track;
            this.in = in;
            this.normalizationData = normalizationData;
            this.episode = null;
        }

        public LoadedStream(@NotNull Metadata.Episode episode, @NotNull GeneralAudioStream in, @Nullable NormalizationData normalizationData) {
            this.episode = episode;
            this.in = in;
            this.normalizationData = normalizationData;
            this.track = null;
        }
    }

    public static class FeederException extends IOException {
        FeederException() {
        }
    }
}
