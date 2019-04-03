package xyz.gianlu.librespot.player.codecs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.common.proto.Metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Gianlu
 */
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
    public static List<Metadata.AudioFile.Format> listFormats(@NotNull Metadata.Track track) {
        List<Metadata.AudioFile.Format> list = new ArrayList<>(track.getFileCount());
        for (Metadata.AudioFile file : track.getFileList()) list.add(file.getFormat());
        return list;
    }

    @NotNull
    public static List<Metadata.AudioFile.Format> listFormats(@NotNull Metadata.Episode episode) {
        List<Metadata.AudioFile.Format> list = new ArrayList<>(episode.getAudioCount());
        for (Metadata.AudioFile file : episode.getAudioList()) list.add(file.getFormat());
        return list;
    }

    @Nullable
    public Metadata.AudioFile getFile(@NotNull Metadata.Track track) {
        for (Metadata.AudioFile file : track.getFileList()) {
            if (file.getFormat() == this.format)
                return file;
        }

        return null;
    }
}
