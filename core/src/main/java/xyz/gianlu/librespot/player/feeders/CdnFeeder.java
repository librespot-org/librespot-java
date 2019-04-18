package xyz.gianlu.librespot.player.feeders;

import okhttp3.Request;
import okhttp3.Response;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.cdn.CdnManager;
import xyz.gianlu.librespot.common.proto.Metadata;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.AbsChunckedInputStream;
import xyz.gianlu.librespot.player.NormalizationData;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public class CdnFeeder extends BaseFeeder {
    private static final Logger LOGGER = Logger.getLogger(CdnFeeder.class);

    public CdnFeeder(@NotNull Session session, @NotNull PlayableId id) {
        super(session, id);
    }

    @Override
    public @NotNull LoadedStream loadTrack(Metadata.@NotNull Track track, Metadata.@NotNull AudioFile file, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, CdnManager.CdnException, MercuryClient.MercuryException {
        byte[] key = session.audioKey().getAudioKey(track.getGid(), file.getFileId());
        CdnManager.Streamer streamer = session.cdn().streamTrack(file, key, haltListener);

        InputStream in = streamer.stream();
        NormalizationData normalizationData = NormalizationData.read(in);
        if (in.skip(0xa7) != 0xa7)
            throw new IOException("Couldn't skip 0xa7 bytes!");

        return new LoadedStream(track, streamer, normalizationData);
    }

    @Override
    public @NotNull LoadedStream loadEpisode(Metadata.@NotNull Episode episode, Metadata.@NotNull AudioFile file, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException, CdnManager.CdnException {
        if (!episode.hasExternalUrl())
            throw new CanNotAvailable("Missing external_url!");

        Response resp = session.cdn().client().newCall(new Request.Builder().head()
                .url(episode.getExternalUrl()).build()).execute();

        if (resp.code() != 200)
            LOGGER.warn("Couldn't resolve redirect!");

        CdnManager.Streamer streamer = session.cdn().streamEpisode(episode, resp.request().url(), haltListener);
        return new LoadedStream(episode, streamer, null);
    }

    public static class CanNotAvailable extends FeederException {
        CanNotAvailable(String message) {
            super(message);
        }
    }
}
