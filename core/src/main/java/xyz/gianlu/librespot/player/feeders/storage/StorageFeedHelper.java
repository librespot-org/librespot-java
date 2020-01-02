package xyz.gianlu.librespot.player.feeders.storage;

import com.spotify.metadata.proto.Metadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.crypto.Packet;
import xyz.gianlu.librespot.player.HaltListener;
import xyz.gianlu.librespot.player.NormalizationData;
import xyz.gianlu.librespot.player.feeders.PlayableContentFeeder;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author Gianlu
 */
public final class StorageFeedHelper {

    private StorageFeedHelper() {
    }

    public static @NotNull PlayableContentFeeder.LoadedStream loadTrack(@NotNull Session session, @NotNull Metadata.Track track, @NotNull Metadata.AudioFile file, @Nullable HaltListener haltListener) throws IOException {
        byte[] key = session.audioKey().getAudioKey(track.getGid(), file.getFileId());
        AudioFileStreaming stream = new AudioFileStreaming(session, file, key, haltListener);
        stream.open();

        session.send(Packet.Type.Unknown_0x4f, new byte[0]);

        InputStream in = stream.stream();
        NormalizationData normalizationData = NormalizationData.read(in);
        if (in.skip(0xa7) != 0xa7) throw new IOException("Couldn't skip 0xa7 bytes!");

        return new PlayableContentFeeder.LoadedStream(track, stream, normalizationData);
    }

    public static @NotNull PlayableContentFeeder.LoadedStream loadEpisode(@NotNull Session session, Metadata.@NotNull Episode episode, Metadata.@NotNull AudioFile file, @Nullable HaltListener haltListener) throws IOException {
        byte[] key = session.audioKey().getAudioKey(episode.getGid(), file.getFileId());
        AudioFileStreaming stream = new AudioFileStreaming(session, file, key, haltListener);
        stream.open();

        InputStream in = stream.stream();
        NormalizationData normalizationData = NormalizationData.read(in);
        if (in.skip(0xa7) != 0xa7) throw new IOException("Couldn't skip 0xa7 bytes!");

        return new PlayableContentFeeder.LoadedStream(episode, stream, normalizationData);
    }
}
