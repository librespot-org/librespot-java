package xyz.gianlu.librespot.player.codecs;

import com.spotify.metadata.Metadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    public static Metadata.AudioFile getAnyVorbisFile(@NotNull List<Metadata.AudioFile> files) {
        for (Metadata.AudioFile file : files) {
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
    public static List<Metadata.AudioFile.Format> listFormats(@NotNull List<Metadata.AudioFile> files) {
        List<Metadata.AudioFile.Format> list = new ArrayList<>(files.size());
        for (Metadata.AudioFile file : files) list.add(file.getFormat());
        return list;
    }

    @Nullable
    public Metadata.AudioFile getFile(@NotNull List<Metadata.AudioFile> files) {
        for (Metadata.AudioFile file : files) {
            if (file.getFormat() == this.format)
                return file;
        }

        return null;
    }
}
