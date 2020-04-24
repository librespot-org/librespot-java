package xyz.gianlu.librespot.player.feeders;

import com.google.protobuf.ByteString;
import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.NotNull;
import xyz.gianlu.librespot.common.Utils;

/**
 * @author Gianlu
 */
public final class StreamId {
    private final ByteString fileId;
    private final ByteString episodeGid;

    public StreamId(@NotNull Metadata.AudioFile file) {
        this.fileId = file.getFileId();
        this.episodeGid = null;
    }

    public StreamId(@NotNull Metadata.Episode episode) {
        this.fileId = null;
        this.episodeGid = episode.getGid();
    }

    @NotNull
    public String getFileId() {
        if (fileId == null) throw new IllegalStateException("Not a file!");
        return Utils.bytesToHex(fileId);
    }

    public boolean isEpisode() {
        return episodeGid != null;
    }

    @NotNull
    public String getEpisodeGid() {
        if (episodeGid == null) throw new IllegalStateException("Not an episode!");
        return Utils.bytesToHex(episodeGid);
    }
}
