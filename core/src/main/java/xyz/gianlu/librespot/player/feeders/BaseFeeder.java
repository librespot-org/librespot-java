package xyz.gianlu.librespot.player.feeders;

import com.spotify.metadata.proto.Metadata;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cdn.CdnManager;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.EpisodeId;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.*;
import xyz.gianlu.librespot.player.codecs.AudioQuality;
import xyz.gianlu.librespot.player.codecs.AudioQualityPreference;
import xyz.gianlu.librespot.player.codecs.SuperAudioFormat;

import java.io.IOException;

/**
 * @author Gianlu
 */
public abstract class BaseFeeder {
    private static final Logger LOGGER = Logger.getLogger(BaseFeeder.class);
    protected final Session session;
    protected final PlayableId id;

    public BaseFeeder(@NotNull Session session, @NotNull PlayableId id) {
        this.session = session;
        this.id = id;
    }

    @NotNull
    public static BaseFeeder feederFor(@NotNull Session session, @NotNull PlayableId id, @NotNull Player.Configuration conf) {
        if (id instanceof TrackId) {
            if (conf.useCdnForTracks()) return new CdnFeeder(session, id);
            else return new StorageFeeder(session, id);
        } else if (id instanceof EpisodeId) {
            if (conf.useCdnForEpisodes()) return new CdnFeeder(session, id);
            else return new StorageFeeder(session, id);
        } else {
            throw new IllegalArgumentException("Unknown PlayableId: " + id);
        }
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
        else if (id instanceof EpisodeId) return loadEpisode((EpisodeId) id, haltListener);
        else throw new IllegalArgumentException("Unknown PlayableId: " + id);
    }

    public final @NotNull LoadedStream loadTrack(@NotNull TrackId id, @NotNull AudioQualityPreference audioQualityPreference, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, MercuryClient.MercuryException, ContentRestrictedException, CdnManager.CdnException {
        Metadata.Track original = session.mercury().sendSync(MercuryRequests.getTrack(id)).proto();
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
    public final LoadedStream loadTrack(@NotNull Metadata.Track track, @NotNull AudioQualityPreference audioQualityPreference, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, CdnManager.CdnException, MercuryClient.MercuryException {
        Metadata.AudioFile file = audioQualityPreference.getFile(track);
        if (file == null) {
            LOGGER.fatal(String.format("Couldn't find any suitable audio file, available: %s", AudioQuality.listFormats(track)));
            throw new FeederException();
        }

        return loadTrack(track, file, haltListener);
    }

    @NotNull
    public abstract LoadedStream loadTrack(@NotNull Metadata.Track track, @NotNull Metadata.AudioFile file, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, CdnManager.CdnException, MercuryClient.MercuryException;

    public final @NotNull LoadedStream loadEpisode(@NotNull EpisodeId id, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException {
        Metadata.Episode episode = session.mercury().sendSync(MercuryRequests.getEpisode(id)).proto();

        Metadata.AudioFile file = null;
        for (Metadata.AudioFile f : episode.getAudioList()) {
            if (!f.hasFormat())
                continue;

            if (SuperAudioFormat.get(f.getFormat()) == SuperAudioFormat.VORBIS) {
                file = f;
                break;
            }
        }

        if (file == null) {
            LOGGER.fatal(String.format("Couldn't find any suitable audio file, available: %s", AudioQuality.listFormats(episode)));
            throw new FeederException();
        }

        return loadEpisode(episode, file, haltListener);
    }

    @NotNull
    public abstract LoadedStream loadEpisode(@NotNull Metadata.Episode episode, @NotNull Metadata.AudioFile file, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, CdnManager.CdnException, MercuryClient.MercuryException;

    public static class LoadedStream {
        public final Metadata.Episode episode;
        public final Metadata.Track track;
        public final GeneralAudioStream in;
        public final NormalizationData normalizationData;

        protected LoadedStream(@NotNull Metadata.Track track, @NotNull GeneralAudioStream in, @Nullable NormalizationData normalizationData) {
            this.track = track;
            this.in = in;
            this.normalizationData = normalizationData;
            this.episode = null;
        }

        protected LoadedStream(@NotNull Metadata.Episode episode, @NotNull GeneralAudioStream in, @Nullable NormalizationData normalizationData) {
            this.episode = episode;
            this.in = in;
            this.normalizationData = normalizationData;
            this.track = null;
        }
    }

    public static class FeederException extends IOException {
        protected FeederException() {
        }

        protected FeederException(String message) {
            super(message);
        }
    }
}
