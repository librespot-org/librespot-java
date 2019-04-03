package xyz.gianlu.librespot.player.feeders;

import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cdn.CdnManager;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.common.proto.Spirc;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.crypto.Packet;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.MercuryRequests;
import xyz.gianlu.librespot.mercury.model.TrackId;
import xyz.gianlu.librespot.player.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Gianlu
 */
public class TrackStreamFeeder {
    private static final Logger LOGGER = Logger.getLogger(TrackStreamFeeder.class);
    private final Session session;

    public TrackStreamFeeder(@NotNull Session session) {
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
    public TrackHandler.LoadedStream load(@NotNull Metadata.Track track, @NotNull Metadata.AudioFile file, boolean cdn) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException {
        GeneralAudioStream generalStream;
        byte[] key = session.audioKey().getAudioKey(track.getGid(), file.getFileId());
        if (cdn) {
            generalStream = session.cdn().stream(file.getFileId(), key);
        } else {
            session.send(Packet.Type.Unknown_0x4f, new byte[0]);

            AudioFileStreaming stream = new AudioFileStreaming(session, file, key);
            stream.open();
            generalStream = stream;
        }

        InputStream in = generalStream.stream();
        NormalizationData normalizationData = NormalizationData.read(in);
        if (in.skip(0xa7) != 0xa7)
            throw new IOException("Couldn't skip 0xa7 bytes!");

        return new TrackHandler.LoadedStream(track, generalStream, normalizationData);
    }

    @NotNull
    public TrackHandler.LoadedStream load(@NotNull Metadata.Track track, @NotNull AudioQualityPreference audioQualityPreference, boolean cdn) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException {
        Metadata.AudioFile file = audioQualityPreference.getFile(track);
        if (file == null) {
            LOGGER.fatal(String.format("Couldn't find any suitable audio file, available: %s", AudioQuality.listFormats(track)));
            throw new FeederException();
        }

        return load(track, file, cdn);
    }

    @NotNull
    public TrackHandler.LoadedStream load(@NotNull TrackId id, @NotNull AudioQualityPreference audioQualityPreference, boolean cdn) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException, ContentRestrictedException {
        Metadata.Track original = session.mercury().sendSync(MercuryRequests.getTrack(id)).proto();
        Metadata.Track track = pickAlternativeIfNecessary(original);
        if (track == null) {
            String country = session.countryCode();
            if (country != null) ContentRestrictedException.checkRestrictions(country, original.getRestrictionList());

            LOGGER.fatal("Couldn't find playable track: " + Utils.bytesToHex(id.getGid()));
            throw new FeederException();
        }

        return load(track, audioQualityPreference, cdn);
    }

    @NotNull
    public TrackHandler.LoadedStream load(@NotNull Spirc.TrackRef ref, @NotNull AudioQualityPreference audioQualityPreference, boolean cdn) throws IOException, MercuryClient.MercuryException, CdnManager.CdnException, ContentRestrictedException {
        return load(TrackId.fromTrackRef(ref), audioQualityPreference, cdn);
    }

    public enum AudioQuality {
        VORBIS_96(Metadata.AudioFile.Format.OGG_VORBIS_96),
        VORBIS_160(Metadata.AudioFile.Format.OGG_VORBIS_160),
        VORBIS_320(Metadata.AudioFile.Format.OGG_VORBIS_320);

        private final Metadata.AudioFile.Format format;

        AudioQuality(@NotNull Metadata.AudioFile.Format format) {
            this.format = format;
        }

        @Nullable
        public static Metadata.AudioFile getAnyVorbisFile(@NotNull Metadata.Track track) {
            for (Metadata.AudioFile file : track.getFileList()) {
                Metadata.AudioFile.Format fmt = file.getFormat();
                if (fmt == Metadata.AudioFile.Format.OGG_VORBIS_96
                        || fmt == Metadata.AudioFile.Format.OGG_VORBIS_160
                        || fmt == Metadata.AudioFile.Format.OGG_VORBIS_320) {
                    return file;
                }
            }

            return null;
        }

        @NotNull
        public static List<Metadata.AudioFile.Format> listFormats(Metadata.Track track) {
            List<Metadata.AudioFile.Format> list = new ArrayList<>(track.getFileCount());
            for (Metadata.AudioFile file : track.getFileList()) list.add(file.getFormat());
            return list;
        }

        @Nullable Metadata.AudioFile getFile(@NotNull Metadata.Track track) {
            for (Metadata.AudioFile file : track.getFileList()) {
                if (file.getFormat() == this.format)
                    return file;
            }

            return null;
        }
    }

    public interface AudioQualityPreference {

        @Nullable
        Metadata.AudioFile getFile(@NotNull Metadata.Track track);
    }

    public static class SuperAudioQuality implements AudioQualityPreference {
        private final SuperAudioFormat format;

        public SuperAudioQuality(@NotNull SuperAudioFormat format) {
            this.format = format;
        }

        @Override
        public @Nullable Metadata.AudioFile getFile(Metadata.@NotNull Track track) {
            for (Metadata.AudioFile file : track.getFileList()) {
                if (SuperAudioFormat.get(file.getFormat()) == format)
                    return file;
            }

            LOGGER.fatal(String.format("Couldn't find any file, format: %s, available: %s", format, AudioQuality.listFormats(track)));
            return null;
        }
    }

    public static class VorbisOnlyAudioQuality implements AudioQualityPreference {
        private final AudioQuality preferred;

        public VorbisOnlyAudioQuality(@NotNull TrackStreamFeeder.AudioQuality preferred) {
            this.preferred = preferred;
        }

        @Override
        public @Nullable Metadata.AudioFile getFile(Metadata.@NotNull Track track) {
            Metadata.AudioFile file = preferred.getFile(track);
            if (file == null) {
                file = AudioQuality.getAnyVorbisFile(track);
                if (file == null) {
                    LOGGER.fatal(String.format("Couldn't find any Vorbis file, available: %s", AudioQuality.listFormats(track)));
                    return null;
                } else {
                    LOGGER.warn(String.format("Using %s because preferred %s couldn't be found.", file, preferred));
                }
            }

            return file;
        }
    }

    public static class FeederException extends IOException {
        FeederException() {
        }
    }
}
