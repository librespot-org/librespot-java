package xyz.gianlu.librespot.player.feeders.cdn;

import com.spotify.metadata.proto.Metadata;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spotify.download.proto.StorageResolve.StorageResolveResponse;
import xyz.gianlu.librespot.common.Utils;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.player.HaltListener;
import xyz.gianlu.librespot.player.NormalizationData;
import xyz.gianlu.librespot.player.feeders.PlayableContentFeeder.LoadedStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public final class CdnFeedHelper {
    private static final Logger LOGGER = Logger.getLogger(CdnFeedHelper.class);

    private CdnFeedHelper() {
    }

    @NotNull
    private static HttpUrl getUrl(@NotNull Session session, @NotNull StorageResolveResponse resp) {
        return HttpUrl.get(resp.getCdnurl(session.random().nextInt(resp.getCdnurlCount())));
    }

    public static @NotNull LoadedStream loadTrack(@NotNull Session session, Metadata.@NotNull Track track, Metadata.@NotNull AudioFile file, @NotNull HttpUrl url, @Nullable HaltListener haltListener) throws IOException, CdnManager.CdnException {
        byte[] key = session.audioKey().getAudioKey(track.getGid(), file.getFileId());
        CdnManager.Streamer streamer = session.cdn().streamFile(file, key, url, haltListener);
        InputStream in = streamer.stream();
        NormalizationData normalizationData = NormalizationData.read(in);
        if (in.skip(0xa7) != 0xa7) throw new IOException("Couldn't skip 0xa7 bytes!");
        return new LoadedStream(track, streamer, normalizationData);
    }

    public static @NotNull LoadedStream loadTrack(@NotNull Session session, Metadata.@NotNull Track track, Metadata.@NotNull AudioFile file, @NotNull StorageResolveResponse storage, @Nullable HaltListener haltListener) throws IOException, CdnManager.CdnException {
        return loadTrack(session, track, file, getUrl(session, storage), haltListener);
    }

    public static @NotNull LoadedStream loadEpisodeExternal(@NotNull Session session, Metadata.@NotNull Episode episode, @Nullable HaltListener haltListener) throws IOException, CdnManager.CdnException {
        try (Response resp = session.client().newCall(new Request.Builder().head()
                .url(episode.getExternalUrl()).build()).execute()) {

            if (resp.code() != 200)
                LOGGER.warn("Couldn't resolve redirect!");

            HttpUrl url = resp.request().url();
            LOGGER.debug(String.format("Fetched external url for %s: %s", Utils.bytesToHex(episode.getGid()), url));

            CdnManager.Streamer streamer = session.cdn().streamExternalEpisode(episode, url, haltListener);
            return new LoadedStream(episode, streamer, null);
        }
    }

    public static @NotNull LoadedStream loadEpisode(@NotNull Session session, Metadata.@NotNull Episode episode, @NotNull Metadata.AudioFile file, @NotNull HttpUrl url, @Nullable HaltListener haltListener) throws IOException, CdnManager.CdnException {
        byte[] key = session.audioKey().getAudioKey(episode.getGid(), file.getFileId());
        CdnManager.Streamer streamer = session.cdn().streamFile(file, key, url, haltListener);
        InputStream in = streamer.stream();
        NormalizationData normalizationData = NormalizationData.read(in);
        if (in.skip(0xa7) != 0xa7) throw new IOException("Couldn't skip 0xa7 bytes!");
        return new LoadedStream(episode, streamer, normalizationData);
    }

    public static @NotNull LoadedStream loadEpisode(@NotNull Session session, Metadata.@NotNull Episode episode, @NotNull Metadata.AudioFile file, @NotNull StorageResolveResponse storage, @Nullable HaltListener haltListener) throws IOException, CdnManager.CdnException {
        return loadEpisode(session, episode, file, getUrl(session, storage), haltListener);
    }
}
