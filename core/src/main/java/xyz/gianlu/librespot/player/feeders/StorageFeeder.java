package xyz.gianlu.librespot.player.feeders;

import com.spotify.metadata.proto.Metadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.crypto.Packet;
import xyz.gianlu.librespot.mercury.model.PlayableId;
import xyz.gianlu.librespot.player.AbsChunckedInputStream;
import xyz.gianlu.librespot.player.NormalizationData;
import xyz.gianlu.librespot.player.feeders.storage.AudioFileStreaming;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public class StorageFeeder extends BaseFeeder {
    public StorageFeeder(@NotNull Session session, @NotNull PlayableId id) {
        super(session, id);
    }

    @Override
    public @NotNull LoadedStream loadTrack(@NotNull Metadata.Track track, @NotNull Metadata.AudioFile file, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException {
        byte[] key = session.audioKey().getAudioKey(track.getGid(), file.getFileId());

        session.send(Packet.Type.Unknown_0x4f, new byte[0]);

        AudioFileStreaming stream = new AudioFileStreaming(session, file, key, haltListener);
        stream.open();

        InputStream in = stream.stream();
        NormalizationData normalizationData = NormalizationData.read(in);
        if (in.skip(0xa7) != 0xa7)
            throw new IOException("Couldn't skip 0xa7 bytes!");

        return new LoadedStream(track, stream, normalizationData);
    }

    @Override
    public @NotNull LoadedStream loadEpisode(Metadata.@NotNull Episode episode, Metadata.@NotNull AudioFile file, @Nullable AbsChunckedInputStream.HaltListener haltListener) throws IOException {
        byte[] key = session.audioKey().getAudioKey(episode.getGid(), file.getFileId());
        AudioFileStreaming stream = new AudioFileStreaming(session, file, key, haltListener);
        stream.open();

        InputStream in = stream.stream();
        NormalizationData normalizationData = NormalizationData.read(in);

        if (in.skip(0xa7) != 0xa7)
            throw new IOException("Couldn't skip 0xa7 bytes!");

        return new LoadedStream(episode, stream, normalizationData);
    }
}
